(ns user
  (:require [ilanga.system :as system]
            [ilanga.db :as db]
            [ilanga.domain.readings :as readings]))
;; Dev namespace — loaded via :dev alias, never compiled into the uberjar.

(defonce store nil)

(defn open!
  "Start the app system, open the home tenant's store over it, ensure the schema.
   Call once at REPL startup: (open!)"
  []
  (let [sys (system/start)
        app (system/app sys)]
    (alter-var-root #'store (constantly (db/open-store app "home")))
    (db/ensure-schema! store)
    :ready))

(defn close!
  "Halt the app system (closes the DuckDB pool + config ds)."
  []
  (system/stop)
  (alter-var-root #'store (constantly nil))
  :stopped)

(comment
  (open!)
  ;; :readings holds the Readings port impl; call the protocol fns on it.
  (readings/latest (:readings store) "home")
  (close!)
  )