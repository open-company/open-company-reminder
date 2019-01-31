(ns oc.reminder.resources.reminder
  (:require [clojure.walk :refer (keywordize-keys)]
            [if-let.core :refer (when-let*)]
            [defun.core :refer (defun defun-)]
            [schema.core :as schema]
            [clj-time.format :as f]
            [java-time :as jt]
            [oc.lib.schema :as lib-schema]
            [oc.lib.text :as oc-str]
            [oc.lib.db.common :as db-common]
            [oc.reminder.resources.user :as user-res]))


;; ----- RethinkDB metadata -----

(def table-name :reminders)
(def primary-key :uuid)

;; ----- Metadata -----

(def reminder-props [:uuid :org-uuid 
                     :headline :author :assignee :assignee-timezone
                     :frequency :week-occurrence :period-occurrence
                     :last-sent :next-send
                     :created-at :updated-at])

(def reserved-properties
  "Properties of a resource that can't be specified during a create and are ignored during an update."
  #{:uuid :org-uuid :author :assignee-timezone :last-sent :next-send :created-at :updated-at})

;; ----- Date / Time hell -----

(def reminder-time (jt/local-time 9)) ; 9AM local to the user in their time zone

(def start-of-quarters #{1 4 7 10}) ; Months that begin a quarter
(def end-of-quarters #{3 6 9 12}) ; Months that end a quarter

(def iso-format (jt/formatter "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")) ; ISO 8601 (Java)
(def joda-iso-format (f/formatters :date-time)) ; ISO 8601 (Joda)
(def UTC "UTC")

(def default-timezone "America/New_York")

;; ----- Utility functions -----

(defn clean
  "Remove any reserved properties from the reminder."
  [reminder]
  (apply dissoc reminder reserved-properties))

(defn add-day
  ([ts] (add-day ts 1))
  ([ts number]
  (-> ts
    (jt/plus (jt/days number))
    (jt/adjust reminder-time))))

(defn- add-author-to-reminder
  "
  Given a reminder, and an update to that reminder, and a user doing the update, add the updating user as
  an additional author on the updated reminder.
  "
  [original-reminder reminder user]
  (let [authors (:author original-reminder)
        ts (db-common/current-timestamp)
        updated-authors (concat authors [(assoc (lib-schema/author-for-user user) :updated-at ts)])]
    (assoc reminder :author updated-authors)))

(defun- adjust-day-of-month
  "
  Given a timestamp, and a time adjustment specified as either a keyword or 2 keywords, perform the
  specified adjustment on the specified timestamp.
  "
  ([ts adjustment :guard sequential?] (jt/adjust ts (first adjustment) (last adjustment)))
  ([ts adjustment] (jt/adjust ts adjustment)))  

(defn- first-day-of-next-month
  "Given a time stamp, keep adding days until you get to the first day of the next month."
  ([ts] (first-day-of-next-month ts ts))
  ([orig-ts updated-ts]
  (if (= (jt/as orig-ts :month-of-year) (jt/as updated-ts :month-of-year)) ; still the same month
    (first-day-of-next-month orig-ts (add-day updated-ts))
    updated-ts))) ; got to the next month

(defn- first-month-of-next-quarter
  "Given a time stamp, keep adding days until you get to the next month, and it's the first month of a quarter."
  ([orig-ts] (first-month-of-next-quarter orig-ts orig-ts))
  ([orig-ts updated-ts]
  (if (or (= (jt/as orig-ts :month-of-year) (jt/as updated-ts :month-of-year)) ; still the same month
          (not (start-of-quarters (jt/as updated-ts :month-of-year)))) ; not a month that starts a quarter
    (first-month-of-next-quarter orig-ts (add-day updated-ts))
    updated-ts)))

(defn- last-month-of-the-quarter
  "Given a time stamp, keep adding days until you get to the next month, and it's the last month of a quarter."
  ([orig-ts] (last-month-of-the-quarter orig-ts orig-ts))
  ([orig-ts updated-ts]
  (if (or (= (jt/as orig-ts :month-of-year) (jt/as updated-ts :month-of-year)) ; still the same month
          (not (end-of-quarters (jt/as updated-ts :month-of-year)))) ; not a month that ends a quarter
    (last-month-of-the-quarter orig-ts (add-day updated-ts))
    updated-ts)))

(defn- adjust-for
  "
  Adjust the specified timestamp, `local-ts`, for the monthly or quarterly frequency and the specified
  period occurrence, using the specified first order adjuster function.
  "
  ([frequency adjuster local-ts occurrence] (adjust-for frequency adjuster local-ts local-ts occurrence))
  ([frequency adjuster orig-ts local-ts occurrence]
  (let [adjustment (case occurrence ; Carrot -> Java-time adjustment mapping
                      :first :first-day-of-month
                      :first-monday [:first-in-month :monday]
                      :last-friday [:last-in-month :friday]
                      :last :last-day-of-month)
        a-send (-> local-ts
                    (adjust-day-of-month adjustment) ; the right day for the reminder
                    (jt/adjust reminder-time))] ; time of the reminder, local to the user
    (if (and ; ALL of these must be true to use the adjusted time as-is, with no further adjustment
             ; it's in the future
             (jt/after? a-send orig-ts) 
             ;; and if it's for the start of a quarter, the month is a quarter starting month
             (not (and (= frequency :quarterly)
                       (or (= occurrence :first) (= occurrence :first-monday))
                       (not (start-of-quarters (jt/as a-send :month-of-year)))))
             ;; and if it's for the end of a quarter, the month is a quarter ending month
             (not (and (= frequency :quarterly)
                       (or (= occurrence :last) (= occurrence :last-friday))
                       (not (end-of-quarters (jt/as a-send :month-of-year))))))
      ;; ALL of them were true, so it's all good
      a-send
      ;; it was in the past (or now), or the wrong month for the quarter, so try again
      (adjust-for frequency adjuster orig-ts (adjuster a-send) occurrence)))))

(defun next-reminder-for
  "
  Pattern matching function that takes a reminder and updates the `:next-send` of the reminder based
  on the frequency and `:week-occurrence` or `:period-occurrence` of the reminder.

  The timestamp to update from can either be specified, or if not specified, the current value of the
  reminder's `:next-send` is used.
  "
  ;; Use the current `:next-send` from the reminder
  ([reminder] (next-reminder-for reminder (:next-send reminder)))
  
  ;; Weekly
  ([reminder :guard #(= (keyword (:frequency %)) :weekly) ts]
  (let [utc-ts (jt/zoned-date-time (f/parse joda-iso-format ts))
        local-ts (jt/with-zone-same-instant utc-ts (:assignee-timezone reminder))
        occurrence (keyword (:week-occurrence reminder))
        a-send (-> local-ts
                    (jt/adjust :next-or-same-day-of-week occurrence)
                    (jt/adjust reminder-time))
        b-send (if (jt/after? a-send local-ts)
                  a-send ; it's in the future
                  (add-day a-send 7)) ; it was in the past (or now)
        next-send (jt/with-zone-same-instant b-send UTC)]
    (assoc reminder :next-send (jt/format iso-format next-send))))

  ;; Bi-weekly
  ([reminder :guard #(= (keyword (:frequency %)) :biweekly) ts]
  (let [utc-ts (jt/zoned-date-time (f/parse joda-iso-format ts))
        local-ts (jt/with-zone-same-instant utc-ts (:assignee-timezone reminder))
        occurrence (keyword (:week-occurrence reminder))
        a-send (-> local-ts
                    (jt/adjust :next-or-same-day-of-week occurrence)
                    (jt/adjust reminder-time))
        b-send (if (jt/after? a-send local-ts)
                  a-send ; it's in the future
                  (add-day a-send 7)) ; it was in the past (or now)
        c-send (add-day b-send 7) ; an extra week for bi-weekly
        next-send (jt/with-zone-same-instant c-send UTC)]
    (assoc reminder :next-send (jt/format iso-format next-send))))

  ;; Monthly
  ([reminder :guard #(= (keyword (:frequency %)) :monthly) ts]
  (let [utc-ts (jt/zoned-date-time (f/parse joda-iso-format ts))
        local-ts (jt/with-zone-same-instant utc-ts (:assignee-timezone reminder))
        occurrence (keyword (:period-occurrence reminder))
        a-send (adjust-for :monthly first-day-of-next-month local-ts occurrence)
        next-send (jt/with-zone-same-instant a-send UTC)]
    (assoc reminder :next-send (jt/format iso-format next-send))))

  ;; Quarterly
  ([reminder :guard #(= (keyword (:frequency %)) :quarterly) ts]
  (let [utc-ts (jt/zoned-date-time (f/parse joda-iso-format ts))
        local-ts (jt/with-zone-same-instant utc-ts (:assignee-timezone reminder))
        occurrence (keyword (:period-occurrence reminder))
        a-send (if (or (= occurrence :last-friday) (= occurrence :last)) 
                  (adjust-for :quarterly last-month-of-the-quarter local-ts occurrence)
                  (adjust-for :quarterly first-month-of-next-quarter local-ts occurrence))
        next-send (jt/with-zone-same-instant a-send UTC)]
    (assoc reminder :next-send (jt/format iso-format next-send)))))

;; ----- Data Schema -----

(def Frequency (schema/pred #(#{:quarterly :monthly :biweekly :weekly} (keyword %))))

(def WeekOccurrence (schema/pred #(#{:monday :tuesday :wednesday :thursday :friday :saturday :sunday} (keyword %))))
(def PeriodOccurrence (schema/pred #(#{:first :first-monday :last-friday :last} (keyword %))))

(def ContributingAuthor
  "An author in a sequence of Authors involved in creating/updating the reminder."
  (merge lib-schema/Author {:updated-at lib-schema/ISO8601}))

(def Reminder {
  :uuid lib-schema/UniqueID
  :org-uuid lib-schema/UniqueID
  :headline lib-schema/NonBlankStr
  :author [ContributingAuthor]
  :assignee lib-schema/Author
  :frequency Frequency
  :week-occurrence WeekOccurrence
  :period-occurrence PeriodOccurrence
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
              author (user-res/author-for user)
              assignee-user (user-res/get-user auth-conn (-> reminder-props :assignee :user-id))
              assignee (user-res/author-for assignee-user)
              assignee-tz (or (:timezone assignee-user) default-timezone)]
    (-> reminder-props
        keywordize-keys
        clean
        (assoc :uuid (db-common/unique-id))
        (assoc :org-uuid org-uuid)
        (update :headline #(or (oc-str/strip-xss-tags %) ""))
        (assoc :author [(assoc author :updated-at ts)])
        (assoc :assignee assignee)
        (assoc :assignee-timezone assignee-tz)
        (update :week-occurrence #(or % :monday))
        (update :period-occurrence #(or % :first))
        (assoc :last-sent nil)
        (assoc :created-at ts)
        (assoc :updated-at ts)
        (next-reminder-for ts))))

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
    (when (= org-uuid (:org-uuid reminder)) reminder)))) ; ensure reminder is for the specified org

(schema/defn ^:always-validate update-reminder! :- (schema/maybe Reminder)
  "
  Given the UUID of the reminder, an updated reminder property map, and a user (as the author),
  update the reminder and return the updated reminder on success.

  Alternatively, given the UUID of the reminder, and a tick timestamp, update the reminder with
  a new `next-send` timestamp and return the updated reminder on success.

  Throws an exception if the merge of the prior reminder and the updated reminder properties doesn't conform
  to the Reminder schema.
  "
  ([conn uuid :- lib-schema/UniqueID ts :- lib-schema/ISO8601]
  {:pre [(db-common/conn? conn)]}
  (when-let* [original-reminder (get-reminder conn uuid)
              next-send-reminder (next-reminder-for original-reminder ts)]
    (schema/validate Reminder next-send-reminder)
    (db-common/update-resource conn table-name primary-key original-reminder next-send-reminder ts)))

  ([conn auth-conn uuid :- lib-schema/UniqueID reminder user :- lib-schema/User]
  {:pre [(db-common/conn? conn)
         (db-common/conn? auth-conn)
         (map? reminder)]}
  (when-let [original-reminder (get-reminder conn uuid)]
    (when-let* [ts (db-common/current-timestamp)
                assignee-user (user-res/get-user auth-conn (-> reminder :assignee :user-id))
                assignee (user-res/author-for assignee-user)
                assignee-tz (:timezone assignee-user)
                assignee-reminder (-> reminder
                                    (assoc :assignee assignee)
                                    (assoc :assignee-timezone assignee-tz))
                authors-reminder (add-author-to-reminder original-reminder assignee-reminder user)
                changed-reminder (merge original-reminder reminder)
                next-send-reminder (if ; the assignee timzone, frequency and occurrence settings didn't change
                                       (= [(:assignee-timezone original-reminder)
                                           (:frequency original-reminder)
                                           (:week-occurrence original-reminder)
                                           (:period-occurrence original-reminder)]
                                          [assignee-tz
                                           (:frequency changed-reminder)
                                           (:week-occurrence changed-reminder)
                                           (:period-occurrence changed-reminder)])
                                      authors-reminder ; then the next-send doesn't need to change
                                      (next-reminder-for authors-reminder ts))] ; otherwise, update the next-send
      (schema/validate Reminder authors-reminder)
      (db-common/update-resource conn table-name primary-key original-reminder next-send-reminder ts)))))

(schema/defn ^:always-validate delete-reminder!
  "Given the UUID of the reminedr, delete the reminder. Return `true` on success."
  [conn uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/delete-resource conn table-name uuid))

;; ----- Collection of reminders -----

(schema/defn ^:always-validate list-reminders [conn org-uuid :- lib-schema/UniqueID]
  {:pre [(db-common/conn? conn)]}
  (db-common/read-resources conn table-name "org-uuid" org-uuid))

(schema/defn ^:always-validate due-reminders [conn ts :- lib-schema/ISO8601]
  {:pre [(db-common/conn? conn)]}
  (db-common/filter-resources conn table-name [{:fn :le :field "next-send" :value ts}]))

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
    :week-occurrence :tuesday})

  (reminder-res/->reminder auth-conn "1234-1234-1234" reminder1 author)

  (reminder-res/create-reminder! conn (reminder-res/->reminder auth-conn "1234-1234-1234" reminder1 author))
  
  (reminder-res/list-reminders conn "1234-1234-1234")
)