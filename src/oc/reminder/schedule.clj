(ns oc.reminder.schedule
  "
  Scheduled reminder runs.

  Dependency note:
  - Java-time lib is used for timezone aware time comparisons.
  - Tick lib is used for scheduling.
  - Tick deprecated the its schedule/timeline API but has not replaced it yet w/ a new design (Jan 3, 2019)
  "
  (:require [taoensso.timbre :as timbre]
            [java-time :as jt]
            [tick.core :as tick]
            [tick.timeline :as timeline]
            [tick.clock :as clock]
            [tick.schedule :as schedule]
            [oc.lib.db.pool :as pool]
            [oc.lib.db.common :as db-common]
            [oc.reminder.resources.reminder :as reminder-res]
            [oc.reminder.async.notification :as notification])
  (:gen-class))

;; ----- State -----

(def reminder-schedule (atom false)) ; atom holding schedule so it can be stopped

(def db-pool (atom false)) ; atom holding DB pool so it can be used in each tick of the schedule

;; ----- Reminder Request Generation -----

(defn- reminder-request [conn reminder instant]
  (if-let [org (first (db-common/read-resources conn "orgs" "uuid" (:org-uuid reminder)))]
    (do
      (timbre/info "Updating reminder:" (:uuid reminder) "in org:" (:uuid org) "with new next send time.")
      (if-let [updated-reminder (reminder-res/update-reminder! conn (:uuid reminder) instant)]
        (do
          (timbre/info "Sending reminder:" (:uuid reminder) "in org:" (:uuid org))
          (notification/send-trigger!
            (assoc-in (notification/->trigger :add org reminder) [:reminder :notification-type] :reminder-alert)))
        (timbre/error "Unable to update reminder:" (:uuid reminder) "in org:" (:org-uuid reminder))))
    (timbre/error "Unable to locate org:" (:org-uuid reminder) "for reminder:" (:uuid reminder))))

(defn- reminder-run [conn instant]
  (let [iso-utc (jt/format reminder-res/iso-format (jt/with-zone-same-instant instant "UTC"))]
    (timbre/info "Checking for reminders due by:" iso-utc)
    (let [due (reminder-res/due-reminders conn iso-utc)
          number (count due)]
      (timbre/info number "Found" (if (= 1 number) "reminder" "reminders") "due by:" iso-utc)
      (doall (pmap #(reminder-request conn % iso-utc) due)))))

;; ----- Scheduled Fns -----

(defn- new-tick?
  "Check if this is a new tick, or if it is just the scheduler catching up with now."
  [tick]
  (.isAfter (.plusSeconds (.toInstant tick) 60) (jt/instant)))

(defn- on-tick [{instant :tick/date}]
  (when (new-tick? instant)
    (timbre/info "New reminder run initiated with tick:" instant)
    (try
      (pool/with-pool [conn @db-pool] (reminder-run conn instant))
      (catch Exception e
        (timbre/error e)))))

;; ----- Scheduler Component -----

(defn- top-of-the-hour [] (jt/plus (jt/truncate-to (jt/zoned-date-time) :minutes) (jt/minutes 1)))

(def hourly-timeline (timeline/timeline (timeline/periodic-seq (top-of-the-hour) (tick/minutes 1)))) ; every hour

(def hourly-schedule (schedule/schedule on-tick hourly-timeline))

(defn start [pool]

  (reset! db-pool pool) ; hold onto the DB pool reference

  (timbre/info "Starting reminder schedule...")
  (timbre/info "First run set for:" (top-of-the-hour))
  (reset! reminder-schedule hourly-schedule)
  (schedule/start hourly-schedule (clock/clock-ticking-in-seconds)))

(defn stop []

  (when @reminder-schedule
    (timbre/info "Stopping reminder schedule...")
    (schedule/stop @reminder-schedule)
    (reset! reminder-schedule false))
  
  (reset! db-pool false))