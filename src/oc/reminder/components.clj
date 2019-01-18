(ns oc.reminder.components
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [org.httpkit.server :as httpkit]
            [oc.lib.db.pool :as pool]
            [oc.reminder.async.notification :as notification]
            [oc.reminder.schedule :as schedule]
            [oc.reminder.config :as c]))

(defrecord HttpKit [options handler server]
  component/Lifecycle
  (start [component]
    (let [handler (get-in component [:handler :handler] handler)
          server  (httpkit/run-server handler options)]
      (assoc component :server server)))
  (stop [component]
    (if-not server
      component
      (do
        (server)
        (dissoc component :server)))))

(defrecord RethinkPool [db-options size regenerate-interval pool]
  component/Lifecycle
  (start [component]
    (timbre/info "[rehinkdb-pool] starting")
    (let [pool (pool/fixed-pool (partial pool/init-conn db-options) pool/close-conn
                                {:size size :regenerate-interval regenerate-interval})]
      (timbre/info "[rehinkdb-pool] started")
      (assoc component :pool pool)))
  (stop [component]
    (if pool
      (do
        (pool/shutdown-pool! pool)
        (dissoc component :pool))
      component)))

(defrecord AsyncConsumers []
  component/Lifecycle

  (start [component]
    (timbre/info "[async-consumers] starting")
    (notification/start) ; core.async channel consumer for notification events
    (timbre/info "[async-consumers] started")
    (assoc component :async-consumers true))

  (stop [{:keys [async-consumers] :as component}]
    (if async-consumers
      (do
        (timbre/info "[async-consumers] stopping")
        (notification/stop) ; core.async channel consumer for notification events
        (timbre/info "[async-consumers] stopped")
        (dissoc component :async-consumers))
    component)))

(defrecord Handler [handler-fn]
  component/Lifecycle
  (start [component]
    (timbre/info "[handler] starting")
    (assoc component :handler (handler-fn component)))
  (stop [component]
    (dissoc component :handler)))

(defrecord Scheduler [db-pool]
  component/Lifecycle
  (start [component]
    (timbre/info "[scheduler] starting")
    (schedule/start (:pool db-pool))
    (assoc component :scheduler true))
  (stop [component]
    (schedule/stop)
    (dissoc component :scheduler false)))

(defn reminder-system [{:keys [port handler-fn]}]
  (component/system-map
    :db-pool (map->RethinkPool {:db-options c/db-options :size c/db-pool-size :regenerate-interval 5})
    :auth-db-pool (map->RethinkPool
                    {:db-options c/auth-db-options :size c/db-pool-size :regenerate-interval 5})
    :async-consumers (component/using
                        (map->AsyncConsumers {})
                        [])
    :scheduler (if (pos? port)
                (component/using
                  (map->Scheduler {})
                  [:db-pool :auth-db-pool])
                "N/A")
    :handler (if (pos? port)
                (component/using
                  (map->Handler {:handler-fn handler-fn})
                  [:db-pool :auth-db-pool])
                "N/A")
    :server  (if (pos? port)
                (component/using
                  (map->HttpKit {:options {:port port}})
                  [:handler])
                "N/A")))