(ns ilanga.protocol.bytes)
;; Pure byte primitives shared by the framer, decoder, and codec fns.
;; No vendor/protocol knowledge here — these are wire-level utilities.

(defn read-uint8 [bs off]
  (bit-and (aget bs off) 0xFF))

(defn read-uint16-be [bs off]
  (bit-or (bit-shift-left (read-uint8 bs off) 8)
          (read-uint8 bs (inc off))))

(defn read-uint32-be [bs off]
  (bit-or (bit-shift-left (read-uint16-be bs off) 16)
          (read-uint16-be bs (+ off 2))))

(defn read-int16-be [bs off]
  (let [u (read-uint16-be bs off)]
    (if (>= u 0x8000) (- u 0x10000) u)))

(defn read-ascii [bs off len]
  (String. bs off len "ASCII"))

(defn subbytes [bs start len]
  (let [out (byte-array len)]
    (System/arraycopy bs start out 0 len)
    out))

;; CRC16 Modbus: poly 0xA001, init 0xFFFF, byte-wise LSB-first walk.
;; Ported from growatt_server.py:91-101. Oracle: crc16("123456789") = 0x4B37.
(defn- crc-step [crc byte]
  (loop [c (bit-xor crc byte) bit 0]
    (if (= bit 8)
      c
      (recur (if (bit-test c 0)
               (bit-xor (bit-shift-right c 1) 0xA001)
               (bit-shift-right c 1))
             (inc bit)))))

(defn crc16-modbus [bs]
  (let [n (count bs)]
    (loop [i 0 crc 0xFFFF]
      (if (>= i n)
        (bit-and crc 0xFFFF)
        (recur (inc i) (crc-step crc (read-uint8 bs i)))))))

;; Repeating-key XOR (symmetric). Key indexed from the payload start (i % len).
;; Ported from growatt_server.py:103-105.
(defn xor-repeating [bs key]
  (let [kbytes (.getBytes key "ASCII")
        klen   (count kbytes)
        n      (count bs)
        out    (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte
                   (bit-xor (read-uint8 bs i)
                            (bit-and (aget kbytes (mod i klen)) 0xFF)))))
    out))