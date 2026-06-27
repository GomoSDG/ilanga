(ns ilanga.test-system
  "Shared harness for integration tests that need a real (temp-path) system.
   Keeps datasource construction out of the individual tests."
  (:require [clojure.java.io :as io]
            [ilanga.system :as system])
  (:import [java.nio.file Files]))

(defn- temp-dir [label]
  (.. (Files/createTempDirectory (str "ilanga-" label)
                                  (into-array java.nio.file.attribute.FileAttribute []))
      toString))

(defn with-test-system
  "Starts a system rooted at temp paths, runs f with the started system, then
   halts and deletes the temp dir. Returns f's result."
  [f]
  (let [dir  (temp-dir "sys")
        boot {:listen-port 5273 :duckdb-dir dir :config-db (str dir "/config.db")}
        sys  (system/start boot)]
    (try
      (f sys)
      (finally
        (system/stop sys)
        (doseq [child (.listFiles (io/file dir))]
          (io/delete-file child true))
        (io/delete-file dir true)))))