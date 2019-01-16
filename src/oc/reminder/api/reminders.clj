(ns oc.reminder.api.reminders
  "Liberator API for reminder resources."
  (:require [if-let.core :refer (if-let*)]
            [compojure.core :as compojure :refer (ANY)]
            [liberator.core :refer (defresource by-method)]
            [taoensso.timbre :as timbre]
            [schema.core :as schema]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.lib.db.common :as db-common]
            [oc.reminder.config :as config]
            [oc.reminder.resources.reminder :as reminder-res]
            [oc.reminder.resources.user :as user-res]
            [oc.reminder.representations.reminder :as reminder-rep]))

;; ----- Validations -----

(defn- allow-user [conn org-uuid user]
  ;; TODO verify user of this org, false if not
  {:access-level :author})

(defn- allow-author [conn org-uuid user]
  ;; TODO verigy author/admin of this org, false if not
  {:access-level :author})

(defn- valid-new-reminder? [auth-conn org-uuid ctx]
  (try
    ;; Create the new reminder from the data provided
    (let [reminder-map (:data ctx)
          author (:user ctx)]
      ;; TODO validate the assignee is an author/admin
      (if-let [assignee (user-res/get-user auth-conn (-> reminder-map :assignee :user-id))] ; the assignee exists?
        {:new-reminder (api-common/rep (reminder-res/->reminder auth-conn org-uuid reminder-map author))}
        [false, {:reason "Assignee is not a valid user."}]))

    (catch clojure.lang.ExceptionInfo e
      [false, {:reason (.getMessage e)}]))) ; Not a valid new reminder

;; ----- Actions -----

(defn create-reminder [conn org-uuid ctx]
  (timbre/info "Creating reminder in org:" org-uuid)
  (if-let* [new-reminder (:new-reminder ctx)
            reminder-result (reminder-res/create-reminder! conn new-reminder)] ; Add the reminder
    (do
      (timbre/info "Created reminder:" (:uuid reminder-result) "in org:" org-uuid)
      {:created-reminder (api-common/rep reminder-result)})
      
    (do (timbre/error "Failed creating reminder for org:" org-uuid) false)))

(defn- delete-reminder [conn org-uuid reminder-uuid ctx]
  (timbre/info "Deleting reminder:" reminder-uuid "for org:" org-uuid)
  (if-let* [org (:existing-org ctx)
            reminder (:existing-reminder ctx)
            _delete-result (reminder-res/delete-reminder! conn reminder-uuid)]
    (timbre/info "Deleted reminder:" reminder-uuid "for org:" org-uuid)
    (do (timbre/error "Deleted reminder:" reminder-uuid "for org:" org-uuid) false)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for operations on a particular reminder
(defresource reminder [conn org-uuid reminder-uuid]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :delete]

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
    :get (fn [ctx] (allow-user conn org-uuid (:user ctx)))
    :patch (fn [ctx] (allow-author conn org-uuid (:user ctx)))
    :delete (fn [ctx] (allow-author conn org-uuid (:user ctx)))})

  ;; Validations
  :processable? (by-method {
    :options true
    :get true
    :patch false ; TODO (fn [ctx] (valid-reminder-update? conn org-uuid reminder-uuid (:data ctx))))
    :delete true})

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [org (or (:existing-org ctx)
                                       (first (db-common/read-resources conn "orgs" "uuid" org-uuid)))
                               reminder (or (:existing-reminder ctx)
                                            (reminder-res/get-reminder conn org-uuid reminder-uuid))]
                        {:existing-org (api-common/rep org) :existing-reminder (api-common/rep reminder)}
                        false))

  ;; Actions
  ; :patch! (fn [ctx] (update-reminder conn org-uuid reminder-uuid ctx)
  :delete! (fn [ctx] (delete-reminder conn org-uuid reminder-uuid ctx))

  ;; Responses
  :handle-ok (by-method {
    :get (fn [ctx] (reminder-rep/render-reminder (:existing-reminder ctx)))
    :patch (fn [ctx] (reminder-rep/render-reminder (:updated-reminder ctx)))})
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
    :get (fn [ctx] (allow-user conn org-uuid (:user ctx)))
    :post (fn [ctx] (allow-author conn org-uuid (:user ctx)))})

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
                                                          (:access-level ctx)))
  :handle-created (fn [ctx] (let [new-reminder (:created-reminder ctx)]
                              (api-common/location-response
                                (reminder-rep/url new-reminder)
                                (reminder-rep/render-reminder new-reminder)
                                reminder-rep/media-type)))
  :handle-unprocessable-entity (fn [ctx]
    (api-common/unprocessable-entity-response (:reason ctx))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)
        auth-db-pool (-> sys :auth-db-pool :pool)]
    (compojure/routes
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
          (reminder conn org-uuid reminder-uuid)))
      (ANY "/orgs/:org-uuid/reminders/:reminder-uuid/"
        [org-uuid reminder-uuid]
        (pool/with-pool [conn db-pool] 
          (reminder conn org-uuid reminder-uuid))))))