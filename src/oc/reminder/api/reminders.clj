(ns oc.reminder.api.reminders
  "Liberator API for reminder resources."
  (:require [if-let.core :refer (if-let*)]
            [compojure.core :as compojure :refer (ANY)]
            [liberator.core :refer (defresource by-method)]
            [taoensso.timbre :as timbre]
            [oc.lib.schema :as lib-schema]
            [schema.core :as schema]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.lib.db.common :as db-common]
            [oc.reminder.config :as config]
            [oc.reminder.async.notification :as notification]
            [oc.reminder.resources.reminder :as reminder-res]
            [oc.reminder.resources.user :as user-res]
            [oc.reminder.representations.reminder :as reminder-rep]
            [oc.reminder.representations.user :as user-rep]))

;; ----- Validations -----

(defn- access-level [conn auth-conn org-uuid user]
  (if-let* [org (first (db-common/read-resources conn "orgs" "uuid" org-uuid [:authors :viewers :team-id]))
            team-id (:team-id org)
            team (db-common/read-resource auth-conn "teams" team-id)
            user-id (:user-id user)]
    (cond
      ((set (:admins team)) user-id) :admin
      ((set (:authors org)) user-id) :author
      ((set (:teams user)) team-id) :viewer
      :else false)))

(defn- allow-user [conn auth-conn org-uuid user]
  (timbre/info "ACCESS LEVEL:" (access-level conn auth-conn org-uuid user))
  (if-let [access-level (access-level conn auth-conn org-uuid user)]
    {:access-level access-level}
    false))

(defn- allow-author [conn auth-conn org-uuid user]
  (if-let* [access-level (access-level conn auth-conn org-uuid user)
            level (#{:admin :author} access-level)]
    {:access-level level}
    false))

(defn- allow-admin-or-party [conn auth-conn org-uuid reminder-uuid user]
  (when-let [reminder (reminder-res/get-reminder conn reminder-uuid)] ; will 404 later if reminder isn't found
    (if-let* [user-id (:user-id user)
              access-level (access-level conn auth-conn org-uuid user)
              level (#{:admin :author} access-level)
              allowed? (or (= level :admin) ; an admin
                           (and (= level :author) ; an author
                                (or (= user-id (-> reminder :assignee :user-id)) ; the asignee
                                    ((set (:authors reminder)) user-id))))] ; an author
      {:access-level level :existing-reminder reminder}
      false)))

(defn- valid-new-reminder? [auth-conn org-uuid ctx]
  (try
    ;; Create the new reminder from the data provided
    (let [reminder-map (:data ctx)
          author (:user ctx)]
      ;; TODO validate the assignee is an author/admin of the org/team
      (if-let [assignee (user-res/get-user auth-conn (-> reminder-map :assignee :user-id))] ; the assignee exists?
        {:new-reminder (api-common/rep (reminder-res/->reminder auth-conn org-uuid reminder-map author))}
        [false, {:reason "Assignee is not a valid user."}]))

    (catch clojure.lang.ExceptionInfo e
      [false, {:reason (.getMessage e)}]))) ; Not a valid new reminder

(defn- valid-reminder-update? [conn auth-conn org-uuid reminder-uuid reminder-props]
  (if-let [existing-reminder (reminder-res/get-reminder conn org-uuid reminder-uuid)]
    ;; Merge the existing reminder with the new updates
    ;; TODO validate the assignee is an author/admin of the org/team
    (if-let* [assignee-id (or (-> reminder-props :assignee :user-id) (-> existing-reminder :assignee :user-id))
              assignee (if (= assignee-id (-> existing-reminder :assignee :user-id))
                          (:assignee existing-reminder)
                          (user-res/get-user auth-conn assignee-id))
              merged-reminder (merge existing-reminder (reminder-res/clean reminder-props))
              updated-reminder (assoc merged-reminder :assignee assignee)]
      (if (lib-schema/valid? reminder-res/Reminder updated-reminder)
        {:existing-reminder (api-common/rep existing-reminder)
         :updated-reminder (api-common/rep updated-reminder)}
        [false, {:updated-reminder (api-common/rep updated-reminder)}])) ; invalid update
    
    true)) ; no existing reminder, so this will fail existence check later and 404

;; ----- Actions -----

(defn create-reminder [conn org-uuid ctx]
  (timbre/info "Creating reminder in org:" org-uuid)
  (if-let* [user (:user ctx)
            org (:existing-org ctx)
            new-reminder (:new-reminder ctx)
            reminder-result (reminder-res/create-reminder! conn new-reminder)] ; Add the reminder
    (do
      (timbre/info "Created reminder:" (:uuid reminder-result) "in org:" org-uuid)
      (notification/send-trigger! (notification/->trigger :add org reminder-result))
      {:created-reminder (api-common/rep reminder-result)})
      
    (do (timbre/error "Failed creating reminder for org:" org-uuid) false)))

(defn- update-reminder [conn auth-conn org-uuid reminder-uuid ctx]
  (timbre/info "Updating reminder:" reminder-uuid "for org:" org-uuid)
  (if-let* [user (:user ctx)
            org (:existing-org ctx)
            reminder (:existing-reminder ctx)
            updated-reminder (:updated-reminder ctx)
            updated-result (reminder-res/update-reminder! conn auth-conn reminder-uuid updated-reminder user)]
    (do
      (timbre/info "Updating reminder:" reminder-uuid "for org:" org-uuid)
      (notification/send-trigger! (notification/->trigger :update org updated-result) reminder)
      {:updated-reminder (api-common/rep updated-result)})

    (do (timbre/error "Failed updating reminder:" reminder-uuid "for org:" org-uuid) false)))

(defn- delete-reminder [conn org-uuid reminder-uuid ctx]
  (timbre/info "Deleting reminder:" reminder-uuid "for org:" org-uuid)
  (if-let* [org (:existing-org ctx)
            reminder (:existing-reminder ctx)
            _delete-result (reminder-res/delete-reminder! conn reminder-uuid)]
    (timbre/info "Deleted reminder:" reminder-uuid "for org:" org-uuid)
    (do (timbre/error "Deleted reminder:" reminder-uuid "for org:" org-uuid) false)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for operations on a particular reminder
(defresource reminder [conn auth-conn org-uuid reminder-uuid]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :patch :delete]

  ;; Media type client accepts
  :available-media-types [reminder-rep/media-type]
  :handle-not-acceptable (api-common/only-accept 406 reminder-rep/media-type)
  
  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :get true
    :patch (fn [ctx] (api-common/known-content-type? ctx reminder-rep/media-type))
    :delete true})

  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-user conn auth-conn org-uuid (:user ctx)))
    :patch (fn [ctx] (allow-admin-or-party conn auth-conn org-uuid reminder-uuid (:user ctx)))
    :delete (fn [ctx] (allow-admin-or-party conn auth-conn org-uuid reminder-uuid (:user ctx)))})

  ;; Validations
  :processable? (by-method {
    :options true
    :get true
    :patch (fn [ctx] (valid-reminder-update? conn auth-conn org-uuid reminder-uuid (:data ctx)))
    :delete true})

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [org (or (:existing-org ctx)
                                       (first (db-common/read-resources conn "orgs" "uuid" org-uuid)))
                               reminder (or (:existing-reminder ctx)
                                            (reminder-res/get-reminder conn org-uuid reminder-uuid))]
                        {:existing-org (api-common/rep org) :existing-reminder (api-common/rep reminder)}
                        false))

  ;; Actions
  :patch! (fn [ctx] (update-reminder conn auth-conn org-uuid reminder-uuid ctx))
  :delete! (fn [ctx] (delete-reminder conn org-uuid reminder-uuid ctx))

  ;; Responses
  :handle-ok (by-method {
    :get (fn [ctx] (reminder-rep/render-reminder (:existing-reminder ctx) (:access-level ctx) (:user ctx)))
    :patch (fn [ctx] (reminder-rep/render-reminder (:updated-reminder ctx) (:access-level ctx) (:user ctx)))})
  :handle-unprocessable-entity (fn [ctx]
    (api-common/unprocessable-entity-response (schema/check reminder-res/Reminder (:updated-reminder ctx)))))

;; A resource for operations on all reminders of a particular org
(defresource reminder-list [conn auth-conn org-uuid]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :post]

  ;; Media type client accepts
  :available-media-types (by-method {
                            :get [reminder-rep/collection-media-type]
                            :post [reminder-rep/media-type]})
  :handle-not-acceptable (by-method {
                            :get (api-common/only-accept 406 reminder-rep/collection-media-type)
                            :post (api-common/only-accept 406 reminder-rep/media-type)})

  ;; Media type client sends
  :known-content-type? (by-method {
                          :options true
                          :get true
                          :post (fn [ctx] (api-common/known-content-type? ctx reminder-rep/media-type))})
  
  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-user conn auth-conn org-uuid (:user ctx)))
    :post (fn [ctx] (allow-author conn auth-conn org-uuid (:user ctx)))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [org (first (db-common/read-resources conn "orgs" "uuid" org-uuid))]
                        {:existing-org (api-common/rep org)}
                        false))

  ;; Validations
  :processable? (by-method {
    :options true
    :get true
    :post (fn [ctx] (valid-new-reminder? auth-conn org-uuid ctx))})

  ;; Actions
  :post! (fn [ctx] (create-reminder conn org-uuid ctx))

  ;; Responses
  :handle-ok (fn [ctx] (reminder-rep/render-reminder-list org-uuid
                                                          (reminder-res/list-reminders conn org-uuid)
                                                          (:access-level ctx) (:user ctx)))
  :handle-created (fn [ctx] (let [new-reminder (:created-reminder ctx)]
                              (api-common/location-response
                                (reminder-rep/url new-reminder)
                                (reminder-rep/render-reminder new-reminder (:access-level ctx) (:user ctx))
                                reminder-rep/media-type)))
  :handle-unprocessable-entity (fn [ctx]
    (api-common/unprocessable-entity-response (:reason ctx))))

;; A resource for operations on all eligible assignees of a particular org
(defresource assignee-list [conn auth-conn org-uuid]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get]

  ;; Media type client accepts
  :available-media-types (by-method {
                            :get [user-rep/collection-media-type]})
  :handle-not-acceptable (by-method {
                            :get (api-common/only-accept 406 user-rep/collection-media-type)})

  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-author conn auth-conn org-uuid (:user ctx)))})

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [org (first (db-common/read-resources conn "orgs" "uuid" org-uuid))
                               assignees (user-res/get-eligible-assignees conn auth-conn org-uuid)]
                        {:existing-org (api-common/rep org) :assignees assignees}
                        false))

  ;; Responses
  :handle-ok (fn [ctx] (user-rep/render-assignee-list org-uuid (:assignees ctx) (:access-level ctx))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)
        auth-db-pool (-> sys :auth-db-pool :pool)]
    (compojure/routes
      ;; Assignee list operations
      (ANY "/orgs/:org-uuid/assignee-roster"
        [org-uuid]
        (pool/with-pool [conn db-pool]
          (pool/with-pool [auth-conn auth-db-pool]
            (assignee-list conn auth-conn org-uuid))))
      (ANY "/orgs/:org-uuid/assignee-roster/"
        [org-uuid]
        (pool/with-pool [conn db-pool]
          (pool/with-pool [auth-conn auth-db-pool]
            (assignee-list conn auth-conn org-uuid))))
      ;; Reminder list operations
      (ANY "/orgs/:org-uuid/reminders"
        [org-uuid]
        (pool/with-pool [conn db-pool]
          (pool/with-pool [auth-conn auth-db-pool]
            (reminder-list conn auth-conn org-uuid))))
      (ANY "/orgs/:org-uuid/reminders/"
        [org-uuid]
        (pool/with-pool [conn db-pool]
          (pool/with-pool [auth-conn auth-db-pool]
            (reminder-list conn auth-conn org-uuid))))
      ;; Reminder operations
      (ANY "/orgs/:org-uuid/reminders/:reminder-uuid"
        [org-uuid reminder-uuid]
        (pool/with-pool [conn db-pool] 
          (pool/with-pool [auth-conn auth-db-pool]
            (reminder conn auth-conn org-uuid reminder-uuid))))
      (ANY "/orgs/:org-uuid/reminders/:reminder-uuid/"
        [org-uuid reminder-uuid]
        (pool/with-pool [conn db-pool] 
          (pool/with-pool [auth-conn auth-db-pool]
            (reminder conn auth-conn org-uuid reminder-uuid)))))))