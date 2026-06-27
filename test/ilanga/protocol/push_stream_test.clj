(ns ilanga.protocol.push-stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [ilanga.protocol.bytes :as b]
            [ilanga.protocol.framer :as framer]
            [ilanga.protocol.decoder :as decoder]
            [ilanga.protocol.push-stream :as ps]
            [ilanga.protocol.sacolar.codec]) ; registers compute-field defmethods (decoder dep)
  (:import [java.io File]
           [java.time Instant]
           [java.util Arrays]))

;; Real device→server captures, untracked (ADR-012). Tests skip gracefully when a
;; capture is absent so a fresh clone still passes. One per control/telemetry type.
(def ^:private captures
  {:announce "proxy_data/proxy_DEVICE->SERVER_20260619_192406_seq00010_type03.bin"
   :ping     "proxy_data/proxy_DEVICE->SERVER_20260619_192405_seq00047_type16.bin"
   :time-rsp "proxy_data/proxy_DEVICE->SERVER_20260619_192431_seq00001_type18.bin"
   :identify "proxy_data/proxy_DEVICE->SERVER_20260619_192405_seq00001_type19.bin"
   :data     "proxy_data/proxy_DEVICE->SERVER_20260620_043519_seq001d4_type04.bin"
   :buffered "proxy_data/proxy_DEVICE->SERVER_20260619_192431_seq00061_type50.bin"})

(def ^:private descriptor-resource "hardware/sacolar-cubewifi.edn")

(defn- capture-bytes [key]
  (let [f (File. (captures key))]
    (when (.exists f)
      (with-open [in (io/input-stream f)]
        (.readAllBytes in)))))

(defn- loaded []
  (let [desc (decoder/load-descriptor descriptor-resource)]
    {:desc desc
     :framing (:framing desc)
     :ps (:push-stream desc)}))

(defn- frame-capture [key]
  (let [{:keys [framing]} (loaded)
        bs (capture-bytes key)]
    (when bs (framer/frame framing bs))))

(deftest classify-routes-by-data-type
  (let [{:keys [ps]} (loaded)]
    (is (= :control   (ps/classify {:push-stream ps} {:type 0x03})) "announce is control")
    (is (= :control   (ps/classify {:push-stream ps} {:type 0x16})) "ping is control")
    (is (= :telemetry (ps/classify {:push-stream ps} {:type 0x04})) "DATA is telemetry")
    (is (= :telemetry (ps/classify {:push-stream ps} {:type 0x50})) "BUFFERED_DATA is telemetry")))

(deftest serial-is-read-from-any-payload
  (if-let [ping (frame-capture :ping)]
    (let [desc (:desc (loaded))]
      (testing "PING carries the serial at :serial (offset 0)"
        (is (= "UMC0D6805H" (ps/serial-from desc ping))))
      (testing "ANNOUNCE carries the same serial"
        (when-let [ann (frame-capture :announce)]
          (is (= "UMC0D6805H" (ps/serial-from desc ann)))))
      (testing "BUFFERED_DATA carries the same serial"
        (when-let [buf (frame-capture :buffered)]
          (is (= "UMC0D6805H" (ps/serial-from desc buf))))))
    (println "[skip] serial-from test — capture absent")))

(deftest announce-serial-is-type-gated
  (if-let [ann (frame-capture :announce)]
    (let [desc (:desc (loaded))]
      (is (= "UMC0D6805H" (ps/announce-serial desc ann)))
      (when-let [ping (frame-capture :ping)]
        (is (nil? (ps/announce-serial desc ping)) "PING is not the announce type"))
      (when-let [data (frame-capture :data)]
        (is (nil? (ps/announce-serial desc data)) "DATA is not the announce type")))
    (println "[skip] announce-serial test — capture absent")))

(deftest ack-for-announces-and-data-is-a-zero-ack
  (let [{:keys [desc framing]} (loaded)]
    (when-let [ann (frame-capture :announce)]
      (let [ack (ps/ack-for desc ann)
            back (framer/frame framing ack)]
        (is (= 0x03 (:type back)) "ack echoes the announce type")
        (is (= (:seq ann) (:seq back)) "ack echoes seq")
        (is (= 0x00 (b/read-uint8 (:payload back) 0)) "announce ack payload is 0x00")))
    (when-let [data (frame-capture :data)]
      (let [ack (ps/ack-for desc data)
            back (framer/frame framing ack)]
        (is (= 0x04 (:type back)))
        (is (= 0x00 (b/read-uint8 (:payload back) 0)))))
    (when-let [rsp (frame-capture :time-rsp)]
      (is (nil? (ps/ack-for desc rsp)) "TIME_SYNC_RSP is not acked"))))

(deftest ack-for-ping-is-a-raw-echo-of-the-received-wire-bytes
  (if-let [bs (capture-bytes :ping)]
    (let [{:keys [desc]} (loaded)
          ping (framer/frame (:framing desc) bs)
          ack  (ps/ack-for desc ping)]
      (is (Arrays/equals bs ack)
          "PING ack (:echo-received) re-encodes to the byte-identical wire packet"))
    (println "[skip] ping-echo test — capture absent")))

(deftest time-sync-bytes-frame-as-type-0x18-with-ascii-utc
  (let [{:keys [desc framing]} (loaded)
        now (Instant/parse "2026-06-27T12:34:56Z")
        wire (ps/time-sync-bytes desc "UMC0D6805H" 0x0001 now)
        back (framer/frame framing wire)]
    (is (= 0x18 (:type back)))
    (is (= 0x0001 (:seq back)))
    (is (= 0x01 (:unit back)))
    (is (= "UMC0D6805H" (ps/serial-from desc back)) "serial leads the payload")
    (is (= 0x1F (b/read-uint8 (:payload back) 31)))
    (is (= 0x13 (b/read-uint8 (:payload back) 33)) "ascii length tag 0x13 = 19")
    (is (= "2026-06-27 12:34:56"
           (b/read-ascii (:payload back) 34 19))
        "ASCII UTC timestamp at payload offset 34")))

(deftest identify-bytes-frame-as-type-0x19-read-registers
  (let [{:keys [desc framing]} (loaded)
        wire (ps/identify-bytes desc "UMC0D6805H" 0x0001)
        back (framer/frame framing wire)]
    (is (= 0x19 (:type back)))
    (is (= 0x0001 (:seq back)))
    (is (= 0x01 (:unit back)))
    (is (= "UMC0D6805H" (ps/serial-from desc back)))
    (is (= 0x04 (b/read-uint8 (:payload back) 31)))
    (is (= 0x00 (b/read-uint8 (:payload back) 32)))
    (is (= 0x15 (b/read-uint8 (:payload back) 33)) "read 21 bytes (0x15) from reg 0x0004")))

(deftest handshake-step-ping-then-announce-then-data
  (let [{:keys [desc]} (loaded)]
    (if-let [ping (frame-capture :ping)]
      (let [ann  (or (frame-capture :announce) ping)
            data (or (frame-capture :data) ping)]
        (testing "first frame (PING) → IDENTIFY + ack, identify now sent"
          (let [{:keys [actions state]} (ps/handshake-step ps/initial-handshake ping desc)]
            (is (= :send-identify (:action (first actions))))
            (is (some #(= :send-ack (:action %)) actions))
            (is (true? (:identify-sent? state)))))
        (testing "ANNOUNCE → ack + time-sync + identity-resolved, phase streaming"
          (let [s1 (assoc ps/initial-handshake :identify-sent? true)
                {:keys [actions state]} (ps/handshake-step s1 ann desc)]
            (is (not (some #(= :send-identify (:action %)) actions)) "identify not re-sent")
            (is (some #(= :send-ack (:action %)) actions))
            (is (some #(= :send-time-sync (:action %)) actions))
            (is (some #(= :identity (:action %)) actions))
            (is (= :streaming (:phase state)))))
        (testing "DATA → ack + forward (telemetry)"
          (let [s2 {:phase :streaming :identify-sent? true}
                {:keys [actions state]} (ps/handshake-step s2 data desc)]
            (is (some #(= :send-ack (:action %)) actions))
            (is (some #(= :forward (:action %)) actions))
            (is (= :streaming (:phase state))))))
      (println "[skip] handshake-step test — capture absent"))))