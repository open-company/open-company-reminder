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
            [oc.lib.db.pool :as pool])
  (:gen-class))

;; ----- State -----

(def reminder-schedule (atom false)) ; atom holding schedule so it can be stopped

(def db-pool (atom false)) ; atom holding DB pool so it can be used in each tick of the schedule

;; ----- Reminder Request Generation -----

(defn- reminder-run [conn instant] nil)

;; ----- Scheduled Fns -----

(defn- on-tick [{instant :tick/date}]
  (timbre/info "New reminder run initiated with tick:" instant)
  (try
    (pool/with-pool [conn @db-pool] (reminder-run conn instant))
    (catch Exception e
      (timbre/error e))))

;; ----- Scheduler Component -----

(defn- top-of-the-hour [] (jt/plus (jt/truncate-to (jt/zoned-date-time) :hours) (jt/hours 1)))

(def hourly-timeline (timeline/timeline (timeline/periodic-seq (top-of-the-hour) (tick/hours 1)))) ; every hour

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