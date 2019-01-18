(ns oc.reminder.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]))

(defn- bool
  "Handle the fact that we may have true/false strings, when we want booleans."
  [val]
  (boolean (Boolean/valueOf val)))

;; ----- System -----

(defonce processors (.availableProcessors (Runtime/getRuntime)))
(defonce core-async-limit (+ 42 (* 2 processors)))

(defonce prod? (= "production" (env :env)))
(defonce intro? (not prod?))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-reminder) false))

;; ----- Logging (see https://github.com/ptaoussanis/timbre) -----

(defonce log-level (or (env :log-level) :info))

;; ----- RethinkDB -----

(defonce migrations-dir "./src/oc/reminder/db/migrations")
(defonce migration-template "./src/oc/reminder/assets/migration.template.edn")

(defonce db-host (or (env :db-host) "localhost"))
(defonce db-port (or (env :db-port) 28015))

(defonce db-name (or (env :db-name) "open_company_storage"))
(defonce auth-db-name (or (env :auth-db-name) "open_company_auth"))

(defonce db-pool-size (or (env :db-pool-size) (- core-async-limit 21))) ; conservative with the core.async limit

(defonce db-map {:host db-host :port db-port :db db-name})
(defonce db-options (flatten (vec db-map))) ; k/v sequence as clj-rethinkdb wants it

(defonce auth-db-map {:host db-host :port db-port :db auth-db-name})
(defonce auth-db-options (flatten (vec auth-db-map))) ; k/v sequence as clj-rethinkdb wants it

;; ----- HTTP server -----

(defonce hot-reload (bool (or (env :hot-reload) false)))
(defonce reminder-server-port (Integer/parseInt (or (env :port) "3011")))

;; ----- URLs -----

(defonce ui-server-url (or (env :ui-server-url) "http://localhost:3559"))

;; ----- Liberator -----

;; see header response, or http://localhost:3001/x-liberator/requests/ for trace results
(defonce liberator-trace (bool (or (env :liberator-trace) false)))
(defonce pretty? (not prod?)) ; JSON response as pretty?

;; ----- AWS SQS -----

(defonce aws-access-key-id (env :aws-access-key-id))
(defonce aws-secret-access-key (env :aws-secret-access-key))

(defonce aws-sqs-notify-queue (env :aws-sqs-notify-queue))

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))