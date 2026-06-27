(ns ilanga.protocol.decoder
  (:require [ilanga.protocol.bytes :as b]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time ZonedDateTime ZoneOffset]))
;; Generic decoder — turns a de-framed packet's payload into a canonical Reading
;; map (measurement + serial + timestamp keys), driven by the descriptor's
;; :fields/:compute/:derive (ADR-033). No vendor/protocol knowledge; computed fields are
;; dispatched to per-protocol codec fns via `compute-field`.

(defmulti compute-field (fn [fn-kw _payload _inputs] fn-kw))

(defn load-descriptor [resource]
  (edn/read-string (slurp (io/resource resource))))

(defn- read-field [bs {:keys [offset type scale]}]
  (let [raw (case type
              :uint8     (b/read-uint8 bs offset)
              :uint16-be (b/read-uint16-be bs offset)
              :int16-be  (b/read-int16-be bs offset)
              :uint32-be (b/read-uint32-be bs offset)
              (throw (ex-info "unsupported field type" {:type type})))]
    (if scale (* raw (double scale)) raw)))

(defn- decode-serial [bs {:keys [offset len]}]
  (str/trim (b/read-ascii bs offset len)))

(defn- decode-timestamp [bs {:keys [fields]}]
  (let [m (into {} (for [{:keys [byte meaning base]} fields]
                     [meaning (let [v (b/read-uint8 bs byte)]
                                (if base (+ v base) v))]))]
    (-> (ZonedDateTime/of (:year-offset m) (:month m) (:day m)
                          (:hour m) (:minute m) (:second m) 0 (ZoneOffset/UTC))
        .toInstant)))

(defn- decode-fields [bs fields]
  (reduce (fn [r spec]
            (assoc r (:reading-key spec) (read-field bs spec)))
          {} fields))

(defn- decode-computed [bs computed]
  (reduce (fn [r spec]
            (assoc r (:reading-key spec)
                   (compute-field (:fn spec) bs (:inputs spec))))
          {} computed))

(defn- decode-derived [reading derived]
  (reduce (fn [r {:keys [reading-key op of]}]
            (assoc r reading-key
                   (case op
                     :sum (reduce + (map #(get r %) of))
                     (throw (ex-info "unsupported derive op" {:op op})))))
          reading derived))

(defn decode
  "Decode a framed packet's payload into a Reading map (no stamp fields).
   `descriptor` is the loaded hardware descriptor; `packet` is the framer output.
   Order is fixed: :fields → :compute → :derive (ADR-033)."
  [descriptor {:keys [payload]}]
  (let [serial   (decode-serial payload (:serial descriptor))
        ts       (decode-timestamp payload (:timestamp descriptor))
        fields   (decode-fields payload (:fields descriptor))
        computed (decode-computed payload (:compute descriptor))
        base     (merge {:reading/device-serial serial :reading/timestamp ts}
                        fields computed)]
    (decode-derived base (:derive descriptor))))