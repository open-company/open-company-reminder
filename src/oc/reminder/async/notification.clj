 (ns oc.reminder.async.notification
   "Async publish of notification events to AWS SQS."
   (:require [clojure.core.async :as async :refer (<! >!!)]
             [taoensso.timbre :as timbre]
             [amazonica.aws.sqs :as sqs]
             [cheshire.core :as json]
             [schema.core :as schema]
             [oc.lib.schema :as lib-schema]
             [oc.reminder.config :as config]
             [oc.reminder.resources.reminder :as reminder-res]))

;; ----- core.async -----

(defonce notification-chan (async/chan 10000)) ; buffered channel

(defonce notification-go (atom true))

;; ----- Data Schema -----

(def NotificationTrigger
  {
    :notification-type (schema/enum :add :update)
    :resource-type (schema/enum :reminder)
    :org {
      :slug lib-schema/NonBlankStr
      :name lib-schema/NonBlankStr
      :uuid lib-schema/UniqueID
      (schema/optional-key :logo-url) (schema/maybe schema/Str)
      (schema/optional-key :logo-width) schema/Int
      (schema/optional-key :logo-height) schema/Int
    }
    :reminder {
      :notification-type (schema/enum :reminder-alert :reminder-notification)
      :headline lib-schema/NonBlankStr
      :frequency reminder-res/Frequency
      (schema/optional-key :week-occurrence) reminder-res/WeekOccurrence
      (schema/optional-key :period-occurrence) reminder-res/PeriodOccurrence
      :assignee lib-schema/Author
      :author lib-schema/Author
      :next-send lib-schema/ISO8601
      :updated-at lib-schema/ISO8601}})

;; ----- Event handling -----

(defn- handle-notification-message
  [trigger]
  (timbre/debug "Notification request of type:" (:notification-type trigger))
  (timbre/trace "Notification request:" trigger)
  (schema/validate NotificationTrigger trigger)
  (timbre/info "Sending request to queue:" config/aws-sqs-notify-queue)
  (sqs/send-message
   {:access-key config/aws-access-key-id
    :secret-key config/aws-secret-access-key}
   config/aws-sqs-notify-queue
   (json/generate-string trigger {:pretty true}))
  (timbre/info "Request sent to:" config/aws-sqs-notify-queue))

;; ----- Event loop -----

(defn- notification-loop []
  (reset! notification-go true)
  (timbre/info "Starting notification...")
  (async/go (while @notification-go
    (timbre/debug "Notification waiting...")
    (let [message (<! notification-chan)]
      (timbre/debug "Processing message on notification channel...")
      (if (:stop message)
        (do (reset! notification-go false) (timbre/info "Notification stopped."))
        (async/thread
          (try
            (handle-notification-message message)
          (catch Exception e
            (timbre/error e)))))))))

;; ----- Notification triggering -----

(defn ->trigger [notification-type org reminder]
  (let [frequency (keyword (:frequency reminder))
        weekly? (or (= frequency :weekly) (= frequency :biweekly))
        occurrence-key (if weekly? :week-occurrence :period-occurrence)]
    (merge {:notification-type notification-type
            :resource-type :reminder
            :org {:slug (:slug org)
                  :name (:name org)
                  :uuid (:uuid org)
                  :logo-url (:logo-url org)
                  :logo-width (:logo-width org)
                  :logo-height (:logo-height org)}}
      {:reminder (-> (select-keys reminder [:headline :frequency :assignee :next-send :updated-at])
                     (assoc :notification-type :reminder-notification) ; the default
                     (assoc :author (dissoc (last (:author reminder)) :updated-at))
                     (assoc occurrence-key (occurrence-key reminder)))})))

(schema/defn ^:always-validate send-trigger! 

  ([trigger :- NotificationTrigger]
  (if (and (not= (-> trigger :reminder :notification-type) :reminder-alert)
           (= (-> trigger :reminder :assignee :user-id) (-> trigger :reminder :author :user-id)))
    (timbre/debug "Skipping notification (self-reminder) for:" trigger)
    (do
      (timbre/debug "Triggering a notification for:" trigger)
      (>!! notification-chan trigger))))

  ([trigger :- NotificationTrigger original-reminder :- reminder-res/Reminder]
  (if (= (-> trigger :reminder :assignee :user-id) (-> original-reminder :reminder :assignee :user-id))
    (timbre/debug "Skipping notification (same assignee) for:" trigger)
    (send-trigger! trigger))))
  
;; ----- Component start/stop -----

(defn start
  "Start the core.async event loop."
  []
  (notification-loop))

(defn stop
  "Stop the the core.async event loop."
  []
  (when @notification-go
    (timbre/info "Stopping notification...")
    (>!! notification-chan {:stop true})))