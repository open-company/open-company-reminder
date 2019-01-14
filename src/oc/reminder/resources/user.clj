(ns oc.reminder.resources.user
  (:require [oc.lib.db.common :as db-common]))

;; ----- RethinkDB metadata -----

(def table-name :users)
(def primary-key :user-id)

;; ----- User Read/Only -----

(defn get-user
  "Given the user-id of the user, retrieve them from the database, or return nil if they don't exist."
  [auth-conn user-id]
  {:pre [(db-common/conn? auth-conn)]}
  (db-common/read-resource auth-conn table-name user-id))