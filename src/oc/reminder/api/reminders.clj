(ns oc.reminder.api.reminders
  "Liberator API for reminder resources."
  (:require [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (ANY)]
            [liberator.core :refer (defresource by-method)]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.db.pool :as pool]
            [oc.lib.db.common :as db-common]
            [oc.lib.api.common :as api-common]
            [oc.reminder.config :as config]
            [oc.reminder.resources.reminder :as reminder-res]
            [oc.reminder.representations.reminder :as reminder-rep]))

;; ----- Validations -----

(defn- allow-user [conn org-uuid user]
  ;; TODO user of this org
  {:access-level :viewer})

(defn- allow-author [conn org-uuid user]
  ;; TODO author/admin of this org
  {:access-level :author})

(defn- valid-new-reminder? [auth-conn org-uuid ctx]
  (try
    ;; Create the new reminder from the data provided
    (let [reminder-map (:data ctx)
          author (:user ctx)]
      {:new-reminder (api-common/rep (reminder-res/->reminder auth-conn org-uuid reminder-map author))})

    (catch clojure.lang.ExceptionInfo e
      [false, {:reason (.getMessage e)}]))) ; Not a valid new reminder

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for operations on a particular reminder
(defresource reminder [conn org-uuid reminder-uuid]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :delete]

  ;; Media type client accepts
  ; :available-media-types [mt/entry-media-type]
  ; :handle-not-acceptable (api-common/only-accept 406 mt/entry-media-type)
  
  ;; Media type client sends
  ; :known-content-type? (by-method {
  ;   :options true
  ;   :get true
  ;   :patch (fn [ctx] (api-common/known-content-type? ctx mt/entry-media-type))
  ;   :delete true})

  ;; Authorization
  ; :allowed? (by-method {
  ;   :options true
  ;   :get (fn [ctx] (access/access-level-for conn org-slug board-slug-or-uuid (:user ctx)))
  ;   :patch (fn [ctx] (access/allow-authors conn org-slug board-slug-or-uuid (:user ctx)))
  ;   :delete (fn [ctx] (access/allow-authors conn org-slug board-slug-or-uuid (:user ctx)))})

  ;; Validations
  ; :processable? (by-method {
  ;   :options true
  ;   :get true
  ;   :patch (fn [ctx] (and (slugify/valid-slug? org-slug)
  ;                         (slugify/valid-slug? board-slug-or-uuid)
  ;                         (valid-entry-update? conn entry-uuid (:data ctx))))
  ;   :delete true})

  ;; Existentialism
  ; :exists? (fn [ctx] (if-let* [org (or (:existing-org ctx)
  ;                                      (org-res/get-org conn org-slug))
  ;                              org-uuid (:uuid org)
  ;                              board (or (:existing-board ctx)
  ;                                        (board-res/get-board conn org-uuid board-slug-or-uuid))
  ;                              entry (or (:existing-entry ctx)
  ;                                        (entry-res/get-entry conn org-uuid (:uuid board) entry-uuid))
  ;                              comments (or (:existing-comments ctx)
  ;                                           (entry-res/list-comments-for-entry conn (:uuid entry)))
  ;                              reactions (or (:existing-reactions ctx)
  ;                                           (entry-res/list-reactions-for-entry conn (:uuid entry)))]
  ;                       {:existing-org (api-common/rep org) :existing-board (api-common/rep board)
  ;                        :existing-entry (api-common/rep entry) :existing-comments (api-common/rep comments)
  ;                        :existing-reactions (api-common/rep reactions)}
  ;                       false))

  ;; Actions
  ; :patch! (fn [ctx] (update-entry conn ctx (s/join " " [org-slug board-slug-or-uuid entry-uuid])))
  ; :delete! (fn [ctx] (delete-entry conn ctx (s/join " " [org-slug board-slug-or-uuid entry-uuid])))

  ;; Responses
  ; :handle-ok (by-method {
  ;   :get (fn [ctx] (entry-rep/render-entry 
  ;                     (:existing-org ctx)
  ;                     (:existing-board ctx)
  ;                     (:existing-entry ctx)
  ;                     (:existing-comments ctx)
  ;                     (reaction-res/aggregate-reactions (:existing-reactions ctx))
  ;                     (:access-level ctx)
  ;                     (-> ctx :user :user-id)))
  ;   :patch (fn [ctx] (entry-rep/render-entry
  ;                       (:existing-org ctx)
  ;                       (:existing-board ctx)
  ;                       (:updated-entry ctx)
  ;                       (:existing-comments ctx)
  ;                       (reaction-res/aggregate-reactions (:existing-reactions ctx))
  ;                       (:access-level ctx)
  ;                       (-> ctx :user :user-id)))})
  ; :handle-unprocessable-entity (fn [ctx]
  ;   (api-common/unprocessable-entity-response (schema/check common-res/Entry (:updated-entry ctx))))

  )

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

  ;; Validations
  :processable? (by-method {
    :options true
    :get true
    :post (fn [ctx] (valid-new-reminder? auth-conn org-uuid ctx))})

  ;; Actions
  ; :post! (fn [ctx] (create-entry conn ctx (s/join " " [org-slug (:slug (:existing-board ctx))])))

  ;; Responses
  :handle-ok (fn [ctx] (reminder-rep/render-reminder-list org-uuid
                                                          (reminder-res/list-reminders conn org-uuid)
                                                          (:access-level ctx)))
  ; :handle-created (fn [ctx] (let [new-entry (:created-entry ctx)
  ;                                 existing-board (:existing-board ctx)]
  ;                             (api-common/location-response
  ;                               (entry-rep/url org-slug (:slug existing-board) (:uuid new-entry))
  ;                               (entry-rep/render-entry (:existing-org ctx) (:existing-board ctx) new-entry [] []
  ;                                 :author (-> ctx :user :user-id))
  ;                               mt/entry-media-type)))
  ; :handle-unprocessable-entity (fn [ctx]
  ;   (api-common/unprocessable-entity-response (:reason ctx)))

  )

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