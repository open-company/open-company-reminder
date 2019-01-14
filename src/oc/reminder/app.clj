(ns oc.reminder.app
  "Namespace for the web application which serves the REST API and starts up the scheduler."
  (:gen-class)
  (:require
    [defun.core :refer (defun-)]
    [if-let.core :refer (if-let*)]
    [raven-clj.core :as sentry]
    [raven-clj.interfaces :as sentry-interfaces]
    [raven-clj.ring :as sentry-mw]
    [taoensso.timbre :as timbre]
    [liberator.dev :refer (wrap-trace)]
    [ring.logger.timbre :refer (wrap-with-logger)]
    [ring.middleware.params :refer (wrap-params)]
    [ring.middleware.reload :refer (wrap-reload)]
    [ring.middleware.cookies :refer (wrap-cookies)]
    [compojure.core :as compojure :refer (GET)]
    [com.stuartsierra.component :as component]
    [oc.lib.sentry-appender :as sa]
    [oc.lib.jwt :as jwt]
    [oc.lib.api.common :as api-common]
    [oc.reminder.components :as components]
    [oc.reminder.config :as c]
    [oc.reminder.api.reminders :as reminders-api]))

;; ----- Unhandled Exceptions -----

;; Send unhandled exceptions to log and Sentry
;; See https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (timbre/error ex "Uncaught exception on" (.getName thread) (.getMessage ex))
     (when c/dsn
       (sentry/capture c/dsn (-> {:message (.getMessage ex)}
                                 (assoc-in [:extra :exception-data] (ex-data ex))
                                 (sentry-interfaces/stacktrace ex)))))))

;; ----- Test Reminder Sending -----

(defonce cookie-name (str c/cookie-prefix "jwt"))

;; ----- Request Routing -----

(defn routes [sys]
  (compojure/routes
    (GET "/ping" [] {:body "OpenCompany Reminder Service: OK" :status 200}) ; Up-time monitor
    (GET "/---error-test---" [] (/ 1 0))
    (GET "/---500-test---" [] {:body "Testing bad things." :status 500})
    (reminders-api/routes sys)))

;; ----- System Startup -----

(defn echo-config [port]
  (println (str "\n"
    "Running on port: " port "\n"
    "Database: " c/db-name "\n"
    "Auth Database: " c/auth-db-name "\n"
    "Database pool: " c/db-pool-size "\n"
    "AWS SQS email queue: " c/aws-sqs-email-queue "\n"
    "AWS SQS bot queue: " c/aws-sqs-bot-queue "\n"
    "Hot-reload: " c/hot-reload "\n"
    "Trace: " c/liberator-trace "\n"
    "Sentry: " c/dsn "\n\n"
    (when c/intro? "Ready to serve...\n"))))

;; Ring app definition
(defn app [sys]
  (cond-> (routes sys)
    c/prod?           api-common/wrap-500 ; important that this is first
    c/dsn             (sentry-mw/wrap-sentry c/dsn) ; important that this is second
    c/prod?           wrap-with-logger
    true              wrap-params
    true              wrap-cookies
    c/liberator-trace (wrap-trace :header :ui)
    c/hot-reload      wrap-reload))

(defn start
  "Start a development server"
  [port]

  ;; Stuff logged at error level goes to Sentry
  (if c/dsn
    (timbre/merge-config!
      {:level (keyword c/log-level)
       :appenders {:sentry (sa/sentry-appender c/dsn)}})
    (timbre/merge-config! {:level (keyword c/log-level)}))

  ;; Start the system
  (let [sys (-> {:handler-fn app :port port}
              components/reminder-system
              component/start)]

    ;; Echo config information
    (println (str "\n"
      (when (and c/intro? (pos? port))
        (str (slurp (clojure.java.io/resource "ascii_art.txt")) "\n"))
      "OpenCompany Reminder Service\n"))
    (echo-config port)))

(defn -main []
  (start c/reminder-server-port))