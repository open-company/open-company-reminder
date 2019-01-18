 (ns oc.reminder.async.notification
   "Async publish of notification events to AWS SQS."
   (:require [clojure.core.async :as async :refer (<! >!!)]
             [taoensso.timbre :as timbre]
             [schema.core :as schema]
             [oc.lib.schema :as lib-schema]
             [oc.reminder.config :as config]
             [oc.reminder.resources.reminder :as reminder-res]))

;; ----- core.async -----

(defonce notification-chan (async/chan 10000)) ; buffered channel

(defonce notification-go (atom true))

;; ----- Data Schema -----

(defn- notification-type? [notification-type] (#{:add :update} notification-type))

(def NotificationTrigger
  {:type (schema/enum :reminder)
   :notification-type (schema/pred notification-type?)
   :org-slug lib-schema/NonBlankStr
   :org-name lib-schema/NonBlankStr
   :org-uuid lib-schema/UniqueID
   (schema/optional-key :logo-url) (schema/maybe schema/Str)
   (schema/optional-key :logo-width) schema/Int
   (schema/optional-key :logo-height) schema/Int
   :headline lib-schema/NonBlankStr
   :frequency lib-schema/NonBlankStr
   (schema/optional-key :week-occurrence) reminder-res/WeekOccurrence
   (schema/optional-key :period-occurrence) reminder-res/PeriodOccurrence
   :assignee lib-schema/Author
   :author lib-schema/Author
   :next-send lib-schema/ISO8601
   :updated-at lib-schema/ISO8601})

;; ----- Event handling -----

(defn- handle-notification-message
  [trigger]
  ; (timbre/debug "Notification request of:" (:notification-type trigger)
  ;              "for:" (trigger :current :uuid) "to topic:" config/aws-sns-storage-topic-arn)
  ; (timbre/trace "Notification request:" trigger)
  ; (schema/validate NotificationTrigger trigger)
  ; (timbre/info "Sending request to topic:" config/aws-sns-storage-topic-arn)
  ; (let [subject (str (name (:notification-type trigger))
  ;                    " on " (name (:resource-type trigger))
  ;                    ": " (-> trigger :current :uuid))
  ;       message (json/generate-string trigger {:pretty true})]
  ;   (try
  ;     (sns/publish
  ;      {:access-key config/aws-access-key-id
  ;       :secret-key config/aws-secret-access-key}
  ;      :topic-arn config/aws-sns-storage-topic-arn
  ;      :subject subject
  ;      :message message)
  ;     (catch Exception e
  ;       (timbre/info "SNS failed with: " e)
  ;       ;; If an exception occurred write to the kinesis firehouse.
  ;       (when config/aws-kinesis-stream-name
  ;         (fh/put-record
  ;          config/aws-kinesis-stream-name
  ;          {:subject subject
  ;           :Message message})))))
  ; (timbre/info "Request sent to topic:" config/aws-sns-storage-topic-arn)
  )

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
    (-> {:type :reminder
         :notification-type notification-type
         :org-slug (:slug org)
         :org-name (:name org)
         :org-uuid (:uuid org)
         :logo-url (:logo-url org)
         :logo-width (:logo-width org)
         :logo-height (:logo-height org)}
      (merge (select-keys reminder [:headline :frequency :assignee :next-send :updated-at]))
      (assoc :author (dissoc (last (:author reminder)) :updated-at))
      (assoc occurrence-key (occurrence-key reminder)))))

(schema/defn ^:always-validate send-trigger! 

  ([trigger :- NotificationTrigger]
  (if (= (-> trigger :assignee :user-id) (-> trigger :author :user-id))
    (timbre/debug "Skipping notification (self-reminder) for:" trigger)
    (do
      (timbre/debug "Triggering a notification for:" trigger)
      (>!! notification-chan trigger))))

  ([trigger :- NotificationTrigger original-reminder :- reminder-res/Reminder]
  (if (= (-> trigger :assignee :user-id) (-> original-reminder :assignee :user-id))
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