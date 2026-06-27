(ns ilanga.protocol.framer
  (:require [ilanga.protocol.bytes :as b]))
;; Generic framer — recovers packet structure from raw wire bytes, parameterized
;; ONLY by the descriptor's :framing block (ADR-034). No vendor/protocol knowledge.
;; The connection drives this and routes its output (TDD-01).

(defn- header-fields [framing]
  ;; :header is [[:seq 2 :be] ...] → [{:name :width :endian :offset}] (offsets derived).
  (loop [specs (:header framing) offset 0 acc []]
    (if (empty? specs)
      acc
      (let [[name width endian] (first specs)]
        (recur (rest specs) (+ offset width)
               (conj acc {:name name :width width :endian endian :offset offset}))))))

(defn- header-size [framing]
  (reduce + (map second (:header framing))))

(defn- widths-by-name [framing]
  (into {} (for [[name width] (:header framing)] [name width])))

(defn- read-header-field [bs {:keys [width offset]}]
  (cond
    (= width 1) (b/read-uint8 bs offset)
    (= width 2) (b/read-uint16-be bs offset)
    :else (throw (ex-info "unsupported header field width" {:width width}))))

(defn- parse-header [framing bs]
  (into {} (for [f (header-fields framing)]
             [(:name f) (read-header-field bs f)])))

(defn- payload-len [framing header]
  ;; payload_len = length − Σ(widths of non-:payload :counts items).
  (let [counts (get-in framing [:length :counts])
        sub    (->> counts
                    (remove #(= % :payload))
                    (map (widths-by-name framing))
                    (reduce +))]
    (- (:length header) sub)))

(defn- crc-verify [framing bs hsize plen]
  (let [algo (get-in framing [:crc :algorithm])]
    (when-not (= algo :crc16-modbus)
      (throw (ex-info "unsupported crc algorithm" {:algorithm algo})))
    (let [computed (b/crc16-modbus (b/subbytes bs 0 (+ hsize plen)))
          trailer  (b/read-uint16-be bs (+ hsize plen))]
      (when (not= computed trailer)
        (throw (ex-info "CRC mismatch" {:computed computed :trailer trailer}))))))

(defn- deobfuscate [framing header enc-payload]
  (let [obf       (:obfuscation framing)
        when-cond (:when obf)
        algo      (:algo obf)
        xor-key   (:key obf)]
    (if (and when-cond (= (get header (key (first when-cond)))
                          (val (first when-cond))))
      (case algo
        :xor-repeating (b/xor-repeating enc-payload xor-key)
        (throw (ex-info "unsupported obfuscation algo" {:algo algo})))
      enc-payload)))

(defn frame
  "De-frame one complete packet. `framing` is the descriptor's :framing block;
   `bs` is the full wire packet (header + encrypted payload + CRC trailer).
   Returns {:seq :proto :length :unit :type :payload <decrypted byte-array>}.
   Throws on length mismatch, CRC failure, or unsupported algorithm."
  [framing bs]
  (let [header    (parse-header framing bs)
        hsize     (header-size framing)
        plen      (payload-len framing header)
        crc-width (get-in framing [:crc :width])
        total     (+ hsize plen crc-width)]
    (when (not= (count bs) total)
      (throw (ex-info "packet length mismatch" {:expected total :actual (count bs)})))
    (crc-verify framing bs hsize plen)
    (let [payload (deobfuscate framing header (b/subbytes bs hsize plen))]
      {:seq     (:seq header)
       :proto   (:proto header)
       :length  (:length header)
       :unit    (:unit header)
       :type    (:type header)
       :payload payload})))