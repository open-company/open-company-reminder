(ns dev
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as ctnrepl]
            [oc.reminder.config :as c]
            [oc.reminder.app :as app]
            [oc.lib.db.pool :as pool]
            [oc.reminder.components :as components]))

(def system nil)
(def conn nil)
(def auth-conn nil)

(defn init
  ([] (init c/reminder-server-port))
  ([port]
  (alter-var-root #'system (constantly (components/reminder-system {:handler-fn app/app
                                                                  :port port})))))

(defn bind-conn! []
  (alter-var-root #'conn (constantly (pool/claim (get-in system [:db-pool :pool]))))
  (alter-var-root #'auth-conn (constantly (pool/claim (get-in system [:auth-db-pool :pool])))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system (fn [s] (when s (component/stop s)))))

(defn go

  ([]
  (go c/reminder-server-port))
  
  ([port]
  (init port)
  (start)
  (bind-conn!)
  (app/echo-config port)
  (println (str "Now serving reminder from the REPL.\n"
                "A DB connection is available with: conn & auth-conn\n"
                "When you're ready to stop the system, just type: (stop)\n"))
  port))

(defn reset []
  (stop)
  (ctnrepl/refresh :after 'user/go))