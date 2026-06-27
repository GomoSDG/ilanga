(ns ilanga.protocol.replay-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [ilanga.protocol.bytes :as b]
            [ilanga.protocol.framer :as framer]
            [ilanga.protocol.decoder :as decoder]
            [ilanga.protocol.growatt.codec] ; registers compute-field defmethods
            [ilanga.domain.readings :as readings]
            [ilanga.ingest :as ingest]
            [ilanga.db :as db])
  (:import [java.io File]
           [java.time Instant]))

;; Real 359-byte wire DATA capture (8 header + 349 payload + 2 CRC). Untracked —
;; tests skip gracefully when absent so a fresh clone still passes.
(def ^:private capture-file
  "proxy_data/proxy_DEVICE->SERVER_20260620_043519_seq001d4_type04.bin")

(def ^:private descriptor-resource "hardware/cubewifi.edn")

(defn- capture-bytes []
  (let [f (File. capture-file)]
    (when (.exists f)
      (with-open [in (io/input-stream f)]
        (.readAllBytes in)))))

;; A Malli-valid Reading for the no-DB domain tests below.
(def ^:private a-reading
  {:reading/timestamp     (Instant/parse "2026-06-20T04:35:19Z")
   :reading/seq           0x01d4
   :reading/device-serial "UMC0D6805H"
   :reading/site-id       "home"
   :reading/hardware-id   :growatt/cubewifi
   :reading/received-at   (Instant/parse "2026-06-20T04:35:20Z")
   :reading/pv1-power-w   100.0
   :reading/pv2-power-w   200.0})

(deftest crc16-modbus-oracles
  (testing "Modbus CRC16 reference vectors"
    (is (= 0xFFFF (b/crc16-modbus (byte-array 0))))
    (is (= 0x4B37 (b/crc16-modbus (.getBytes "123456789"))))))

(deftest framer-decodes-real-data-packet
  (if-let [bs (capture-bytes)]
    (let [desc (decoder/load-descriptor descriptor-resource)
          pkt  (framer/frame (:framing desc) bs)]
      (testing "header parsed from sequential widths"
        (is (= 0x01d4 (:seq pkt)))
        (is (= 0x0006 (:proto pkt)))
        (is (= 0x04   (:type pkt)))
        (is (= 349    (count (:payload pkt)))))
      (testing "CRC verified and payload decrypted"
        (is (= "UMC0D6805H"
               (str/trim (b/read-ascii (:payload pkt) 0 10))))))
    (println "[skip] framer test — capture absent:" capture-file)))

(deftest decoder-produces-canonical-reading
  (if-let [bs (capture-bytes)]
    (let [desc (decoder/load-descriptor descriptor-resource)
          pkt  (framer/frame (:framing desc) bs)
          r    (decoder/decode desc pkt)]
      (is (= "UMC0D6805H" (:reading/device-serial r)))
      (is (inst? (:reading/timestamp r)))
      (is (number? (:reading/pv1-power-w r)))
      (is (number? (:reading/pv2-power-w r)))
      (testing ":derive — pv-total = pv1 + pv2"
        (is (= (+ (:reading/pv1-power-w r) (:reading/pv2-power-w r))
               (:reading/pv-total-power-w r))))
      (testing ":compute — battery power/current"
        (is (number? (:reading/battery-power-w r)))
        (is (number? (:reading/battery-current-a r)))))
    (println "[skip] decoder test — capture absent:" capture-file)))

;; --- Domain tests: protocol port, no DB, no with-redefs (ADR-035) ---

(deftest ingest-appends-through-the-port
  (testing "ingest-reading calls the port's append with the validated reading"
    (let [appended (atom nil)
          fake (reify readings/Readings
                 (latest   [_ _] nil)
                 (in-range [_ _ _ _] [])
                 (append   [_ r] (reset! appended r) ::appended))]
      (is (= ::appended (ingest/ingest-reading fake a-reading)))
      (is (= a-reading @appended) "the reading reached the port untouched"))))

(deftest ingest-rejects-an-invalid-reading
  (testing "the Malli precondition fires before any port call"
    (let [fake (reify readings/Readings
                 (latest [_ _] nil)
                 (in-range [_ _ _ _] [])
                 (append [_ _] (throw (ex-info "must not be called" {}))))]
      (is (thrown? AssertionError
                   (ingest/ingest-reading fake {:reading/timestamp "not an instant"})))
      (is (= "UMC0D6805H" (:reading/device-serial a-reading))))))

;; --- Adapter integration test: the DuckDB realization (files named here,
;;     correctly — this tests the adapter, not the domain). ---

(deftest persist-end-to-end
  (if-let [bs (capture-bytes)]
    (let [desc    (decoder/load-descriptor descriptor-resource)
          pkt     (framer/frame (:framing desc) bs)
          base    (decoder/decode desc pkt)
          reading (merge base
                         {:reading/seq         (:seq pkt)
                          :reading/hardware-id :growatt/cubewifi
                          :reading/site-id     "home"
                          :reading/received-at (Instant/now)})
          tenant  "test-replay"]
      (is (readings/valid? reading) "stamped reading conforms to Malli schema")
      (try
        (let [store (db/open-store {:tenant-id tenant})
              port  (:readings store)]
          (db/ensure-schema! store)
          (ingest/ingest-reading port reading)
          (let [row (readings/latest port "home")]
            (is (= "UMC0D6805H" (:device_serial row)))
            (is (number? (:pv_total_power_w row)))
            (is (number? (:battery_power_w row)))))
        (finally
          (io/delete-file (str "data/" tenant ".ddb") true)
          (io/delete-file (str "data/" tenant ".ddb.wal") true))))
    (println "[skip] persist test — capture absent:" capture-file)))