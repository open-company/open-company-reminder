(ns oc.reminder.resources.user
  (:require [if-let.core :refer (when-let*)]
            [schema.core :as schema]
            [oc.lib.jwt :as jwt]
            [oc.lib.schema :as lib-schema]
            [oc.lib.db.common :as db-common]))

;; ----- RethinkDB metadata -----

(def table-name :users)
(def primary-key :user-id)

;; ----- Utility functions -----

(defn author-for [user]
  (-> user
    (lib-schema/author-for-user)
    (update :name #(or % (jwt/name-for user)))))

;; ----- Users Read/Only -----

(schema/defn ^:always-validate get-eligible-assignees
  "Given the org-id of the org, get all the eligible reminder assignees."
  [conn auth-conn org-uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)
         (db-common/conn? auth-conn)]}
  (when-let* [org (first (db-common/read-resources conn "orgs" "uuid" org-uuid))
              author-ids (:authors org)
              authors (db-common/read-resources auth-conn "users" "user-id" author-ids)
              active-authors (filter #(= "active" (:status %)) authors)]
    (map author-for active-authors)))

(schema/defn ^:always-validate get-user
  "Given the user-id of the user, retrieve them from the database, or return nil if they don't exist."
  [auth-conn user-id :- lib-schema/UniqueID]
  {:pre [(db-common/conn? auth-conn)]}
  (db-common/read-resource auth-conn table-name user-id))