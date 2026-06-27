(ns ilanga.protocol.sacolar.codec
  (:require [ilanga.protocol.bytes :as b]
            [ilanga.protocol.decoder :as decoder]))
;; Per-protocol computed-field fns for the Sacolar inverter, reached through a
;; Growatt-family CubeWiFi datalogger (ADR-033 :compute methods). The wire
;; framing is shared CubeWiFi (ADR-034); only the payload offset map and these
;; codec algorithms are vendor-specific. Each fn is pure (payload, inputs) →
;; value; offsets come from the descriptor's :inputs, so a protocol-doc offset
;; change is an edn edit, not a code edit.
;; Algorithms: doc/protocol/sacolar-cubewifi-data-payload.md "Battery power &
;; current decode". Convention: + = discharging, − = charging.

(defmethod decoder/compute-field :ilanga.protocol.sacolar.codec/battery-power
  [_ payload {:keys [power-mag charge-i discharge-i idle-flag hysteresis-a]}]
  (let [raw       (b/read-uint16-be payload power-mag)
        signed    (b/read-int16-be payload power-mag)
        charge    (/ (b/read-uint16-be payload charge-i) 10.0)
        discharge (/ (b/read-uint16-be payload discharge-i) 10.0)
        idle      (= (b/read-uint8 payload idle-flag) 0xFF)
        hyst      (or hysteresis-a 0.5)]
    (cond
      ;; charging → negative
      (and (> charge hyst) (< discharge hyst))
      (if (>= raw 0x8000) (/ signed 10.0) (- (/ raw 10.0)))

      ;; discharging → positive, read UNSIGNED (overflow fix: raw ≥ 0x8000 is a
      ;; discharge > 3276.7 W wrapping, not a sign bit)
      (and (> discharge hyst) (< charge hyst))
      (/ raw 10.0)

      ;; idle / ambiguous
      :else
      (if idle 0.0 (/ signed 10.0)))))

(defmethod decoder/compute-field :ilanga.protocol.sacolar.codec/battery-current
  [_ payload {:keys [charge-i discharge-i]}]
  ;; signed net current, + = discharging, rounded to 0.01 A.
  (let [amps (/ (- (b/read-uint16-be payload discharge-i)
                   (b/read-uint16-be payload charge-i)) 10.0)]
    (/ (Math/round (* amps 100.0)) 100.0)))