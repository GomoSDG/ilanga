(ns user
  (:require [ilanga.db :as db]
            [ilanga.domain.readings :as readings]))
;; Dev namespace — loaded via :dev alias, never compiled into the uberjar.

(defonce store nil)

(defn open!
  "Open the store for the home tenant and ensure the schema exists.
   Call once at REPL startup: (open!)"
  []
  (alter-var-root #'store
                  (constantly (db/open-store {:tenant-id "home"})))
  (db/ensure-schema! store)
  :ready)

(comment
  (open!)
  (readings/latest store "home")
  )
