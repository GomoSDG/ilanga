(ns ilanga.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [ilanga.system :as system]
            [ilanga.db :as db]
            [ilanga.domain.readings :as readings]
            [ilanga.ingest :as ingest]
            [ilanga.test-system :refer [with-test-system]])
  (:import [java.time Instant]))

(defn- a-reading [ts site-id]
  {:reading/timestamp     (Instant/parse ts)
   :reading/seq            0x0001
   :reading/device-serial  "TEST-SERIAL"
   :reading/site-id        site-id
   :reading/hardware-id    :growatt/cubewifi
   :reading/received-at    (Instant/parse ts)
   :reading/pv1-power-w    100.0
   :reading/pv2-power-w     200.0
   :reading/pv-total-power-w 300.0})

(deftest system-starts-both-components
  (with-test-system
   (fn [sys]
     (testing "the Integrant graph starts the config-ds and duckdb-pool"
       (is (some? (:ilanga/config-ds sys)))
       (is (some? (:ilanga/duckdb-pool sys)))
       (is (instance? ilanga.db.DuckDbPool (:ilanga/duckdb-pool sys)))))))

(deftest open-store-round-trips-through-the-pool
  (with-test-system
   (fn [sys]
     (testing "open-store layers a TenantStore over the boot datasources"
       (let [app    (system/app sys)
             store  (db/open-store app "home")
             port   (:readings store)]
         (db/ensure-schema! store)
         (ingest/ingest-reading port (a-reading "2026-06-27T08:00:00Z" "home"))
         (let [r (readings/latest port "home")]
           (is (= "TEST-SERIAL" (:reading/device-serial r)))
           (is (= 300.0 (:reading/pv-total-power-w r)))
           (is (readings/valid? r) "read-side Reading round-trips through valid?")))))))

(deftest stop-empties-the-duckdb-pool
  (with-test-system
   (fn [sys]
     (let [app   (system/app sys)
           store (db/open-store app "home")]
       (db/ensure-schema! store)
       (testing "the pool caches one datasource after open-store"
         (is (= 1 (count @(:cache (:ilanga/duckdb-pool sys))))))
       (system/stop sys)
       (testing "halt empties the pool"
         (is (zero? (count @(:cache (:ilanga/duckdb-pool sys))))))))))