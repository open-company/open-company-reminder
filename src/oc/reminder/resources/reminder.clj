(ns oc.reminder.resources.reminder
  (:require [clojure.walk :refer (keywordize-keys)]
            [if-let.core :refer (when-let*)]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.text :as oc-str]
            [oc.lib.db.common :as db-common]
            [oc.lib.jwt :as jwt]
            [oc.reminder.resources.user :as user-res]))

(def reminder-props [:uuid :org-uuid 
                     :headline :author :assignee :assignee-timezone
                     :frequency :week-occurence :period-occurence
                     :last-sent :next-send
                     :created-at :updated-at])

;; ----- RethinkDB metadata -----

(def table-name :reminders)
(def primary-key :uuid)

;; ----- Metadata -----

(def reserved-properties
  "Properties of a resource that can't be specified during a create and are ignored during an update."
  #{:uuid :org-uuid :author :assignee-timezone :last-sent :next-send :created-at :updated-at})

;; ----- Utility functions -----

(defn clean
  "Remove any reserved properties from the reminder."
  [reminder]
  (apply dissoc reminder reserved-properties))

(defn- author-for [user]
  (-> user
    (lib-schema/author-for-user)
    (assoc :name (jwt/name-for user))))

;; ----- Data Schema -----

(def Frequency (schema/pred #(#{:quarterly :monthly :biweekly :weekly} (keyword %))))

(def WeekOccurence (schema/pred #(#{:monday :tuesday :wednesday :thursday :friday :saturday :sunday} (keyword %))))
(def PeriodOccurence (schema/pred #(#{:first :first-monday :last-friday :last} (keyword %))))

(def Reminder {
  :uuid lib-schema/UniqueID
  :org-uuid lib-schema/UniqueID
  :headline lib-schema/NonBlankStr
  :author lib-schema/Author
  :assignee lib-schema/Author
  :frequency Frequency
  :week-occurence WeekOccurence
  :period-occurence PeriodOccurence
  :assignee-timezone lib-schema/NonBlankStr
  :last-sent (schema/maybe lib-schema/ISO8601)
  :next-send lib-schema/ISO8601
  :created-at lib-schema/ISO8601
  :updated-at lib-schema/ISO8601})

;; ----- Reminder CRUD -----

(schema/defn ^:always-validate ->reminder :- Reminder
  "
  Takes an org UUID, a user (as the author), and a minimal map describing a Reminder,
  and 'fill the blanks' with any missing properties.
  "
  [auth-conn org-uuid :- lib-schema/UniqueID reminder-props user :- lib-schema/User]
  {:pre [(db-common/conn? auth-conn)
         (map? reminder-props)]}
  (when-let* [ts (db-common/current-timestamp)
              author (author-for user)
              assignee-user (user-res/get-user auth-conn (-> reminder-props :assignee :user-id))
              assignee (author-for assignee-user)
              assignee-tz (:timezone assignee-user)]
    (-> reminder-props
        keywordize-keys
        clean
        (assoc :uuid (db-common/unique-id))
        (assoc :org-uuid org-uuid)
        (update :headline #(or (oc-str/strip-xss-tags %) ""))
        (assoc :author author)
        (assoc :assignee assignee)
        (assoc :assignee-timezone assignee-tz)
        (update :week-occurence #(or % :monday))
        (update :period-occurence #(or % :first))
        (assoc :last-sent nil)
        (assoc :next-send ts) ; TODO
        (assoc :created-at ts)
        (assoc :updated-at ts))))

(schema/defn ^:always-validate create-reminder!
  "Create a reminder in the system. Throws a runtime exception if the reminder doesn't conform to the Reminder schema."
  [conn reminder :- Reminder]
  {:pre [(db-common/conn? conn)]}
  (db-common/create-resource conn table-name reminder (db-common/current-timestamp)))

(schema/defn ^:always-validate get-reminder  :- (schema/maybe Reminder)
  "
  Given the UUID of the reminder, and optionally, the UUID of the org, retrieve the reminder,
  or return nil if it doesn't exist or the reminder is not for the specified org.
  "
  ([conn uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/read-resource conn table-name uuid))

  ([conn org-uuid :- lib-schema/UniqueID uuid :- lib-schema/UniqueID]
  (when-let [reminder (get-reminder conn uuid)]
    (if (= org-uuid (:org-uuid reminder)) ; ensure reminder is for the specified org
      reminder
      nil))))

(schema/defn ^:always-validate delete-reminder!
  "Given the UUID of the reminedr, delete the reminder. Return `true` on success."
  [conn uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-resource conn table-name uuid))

;; ----- Collection of reminders -----

(schema/defn ^:always-validate list-reminders [conn org-uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/read-resources conn table-name "org-uuid" org-uuid))

;; ----- Armageddon -----

(defn delete-all-reminders!
  [conn]
  {:pre [(db-common/conn? conn)]}
  ;; Delete all interactions and entries
  (db-common/delete-all-resources! conn table-name))

;; ----- REPL -----

(comment

  (def user-id "STORED-USERS-ID")

  (def author {
    :name "Albert Camus"
    :avatar-url "https://images-na.ssl-images-amazon.com/images/M/MV5BZTcwZGVlOGEtYTc1My00MmU1LTkwNWEtYWIwNDk1NzExODBlL2ltYWdlL2ltYWdlXkEyXkFqcGdeQXVyNDkzNTM2ODg@._V1_UY317_CR142,0,214,317_AL_.jpg"
    :user-id user-id})

  (def reminder1 {
    :headline "Don't forget to feed the ferrets"
    :assignee {:user-id user-id}
    :frequency :weekly
    :week-occurence :tuesday})

  (reminder-res/->reminder auth-conn "1234-1234-1234" reminder1 author)

  (reminder-res/create-reminder! conn (reminder-res/->reminder auth-conn "1234-1234-1234" reminder1 author))
  
  (reminder-res/list-reminders conn "1234-1234-1234")
)