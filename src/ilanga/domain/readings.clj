(ns ilanga.domain.readings
  (:require [next.jdbc :as jdbc]
            [malli.core :as m])
  (:import [java.sql Timestamp]))
;; DOMAIN — data layer. No ilanga.engine / ilanga.db / ilanga.ingestion requires allowed here.

(def Reading
  [:map
   [:reading/timestamp     inst?]
   [:reading/seq           int?]
   [:reading/device-serial string?]
   [:reading/site-id       string?]
   [:reading/hardware-id   keyword?]
   [:reading/received-at   inst?]
   ;; Measurement vocabulary — optional; absent = device's descriptor doesn't produce it.
   ;; KPI layer reports :kpi/available? false for absent fields (ADR-010).
   [:reading/pv1-voltage-v        {:optional true} number?]
   [:reading/pv2-voltage-v        {:optional true} number?]
   [:reading/pv1-power-w          {:optional true} number?]
   [:reading/pv2-power-w          {:optional true} number?]
   [:reading/pv1-current-a        {:optional true} number?]
   [:reading/pv2-current-a        {:optional true} number?]
   [:reading/load-power-w         {:optional true} number?]
   [:reading/ac-apparent-power-va {:optional true} number?]
   [:reading/grid-power-w         {:optional true} number?]
   [:reading/battery-voltage-v    {:optional true} number?]
   [:reading/battery-power-w      {:optional true} number?]
   [:reading/battery-current-a    {:optional true} number?]
   [:reading/ac-input-voltage-v   {:optional true} number?]
   [:reading/ac-output-voltage-v  {:optional true} number?]
   [:reading/ac-input-current-a   {:optional true} number?]
   [:reading/grid-frequency-hz    {:optional true} number?]
   [:reading/temp-c               {:optional true} number?]
   [:reading/energy-today-kwh     {:optional true} number?]
   [:reading/energy-total-kwh     {:optional true} number?]
   [:reading/pv-total-power-w     {:optional true} number?]])

(defn valid? [reading]
  (m/validate Reading reading))

(defn latest
  "Most-recent Reading for site-id across all devices at the site."
  [tenant-store site-id]
  (first
   (jdbc/execute! (:time-series tenant-store)
                  ["SELECT * FROM readings
                    WHERE site_id = ?
                    ORDER BY ts DESC LIMIT 1" site-id])))

(defn in-range
  "Readings for site-id with ts ∈ [from, to), ascending. from/to are java.time.Instant."
  [tenant-store site-id from to]
  (jdbc/execute! (:time-series tenant-store)
                 ["SELECT * FROM readings
                   WHERE site_id = ?
                     AND ts >= CAST(? AS TIMESTAMPTZ)
                     AND ts <  CAST(? AS TIMESTAMPTZ)
                   ORDER BY ts ASC" site-id from to]))

(defn- reading->row [r]
  {:ts                   (Timestamp/from (:reading/timestamp r))
   :seq                  (:reading/seq r)
   :device_serial        (:reading/device-serial r)
   :site_id              (:reading/site-id r)
   :hardware_id          (name (:reading/hardware-id r))
   :received_at          (Timestamp/from (:reading/received-at r))
   :pv1_voltage_v        (:reading/pv1-voltage-v r)
   :pv2_voltage_v        (:reading/pv2-voltage-v r)
   :pv1_power_w          (:reading/pv1-power-w r)
   :pv2_power_w          (:reading/pv2-power-w r)
   :pv1_current_a        (:reading/pv1-current-a r)
   :pv2_current_a        (:reading/pv2-current-a r)
   :load_power_w         (:reading/load-power-w r)
   :ac_apparent_power_va (:reading/ac-apparent-power-va r)
   :grid_power_w         (:reading/grid-power-w r)
   :battery_voltage_v    (:reading/battery-voltage-v r)
   :battery_power_w      (:reading/battery-power-w r)
   :battery_current_a    (:reading/battery-current-a r)
   :ac_input_voltage_v   (:reading/ac-input-voltage-v r)
   :ac_output_voltage_v  (:reading/ac-output-voltage-v r)
   :ac_input_current_a   (:reading/ac-input-current-a r)
   :grid_frequency_hz    (:reading/grid-frequency-hz r)
   :temp_c               (:reading/temp-c r)
   :energy_today_kwh     (:reading/energy-today-kwh r)
   :energy_total_kwh     (:reading/energy-total-kwh r)
   :pv_total_power_w     (:reading/pv-total-power-w r)})

(defn write!
  "Append a Reading. Idempotent on exact replay (ON CONFLICT DO NOTHING).
   TODO: detect differing same-identity rows and dead-letter them (TDD-02 sequence)."
  [tenant-store reading]
  {:pre [(valid? reading)]}
  (let [row (reading->row reading)]
    (jdbc/execute-one!
     (:time-series tenant-store)
     ["INSERT INTO readings
         (ts, seq, device_serial, site_id, hardware_id, received_at,
          pv1_voltage_v, pv2_voltage_v, pv1_power_w, pv2_power_w,
          pv1_current_a, pv2_current_a, load_power_w, ac_apparent_power_va,
          grid_power_w, battery_voltage_v, battery_power_w, battery_current_a,
          ac_input_voltage_v, ac_output_voltage_v, ac_input_current_a,
          grid_frequency_hz, temp_c, energy_today_kwh, energy_total_kwh,
          pv_total_power_w)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
       ON CONFLICT (device_serial, ts) DO NOTHING"
      (:ts row) (:seq row) (:device_serial row) (:site_id row)
      (:hardware_id row) (:received_at row)
      (:pv1_voltage_v row) (:pv2_voltage_v row)
      (:pv1_power_w row) (:pv2_power_w row)
      (:pv1_current_a row) (:pv2_current_a row)
      (:load_power_w row) (:ac_apparent_power_va row)
      (:grid_power_w row) (:battery_voltage_v row)
      (:battery_power_w row) (:battery_current_a row)
      (:ac_input_voltage_v row) (:ac_output_voltage_v row)
      (:ac_input_current_a row) (:grid_frequency_hz row)
      (:temp_c row) (:energy_today_kwh row) (:energy_total_kwh row)
      (:pv_total_power_w row)])))

(def create-table-ddl
  "CREATE TABLE IF NOT EXISTS readings (
     ts                   TIMESTAMPTZ NOT NULL,
     seq                  INTEGER     NOT NULL,
     device_serial        VARCHAR     NOT NULL,
     site_id              VARCHAR     NOT NULL,
     hardware_id          VARCHAR     NOT NULL,
     received_at          TIMESTAMPTZ NOT NULL,
     pv1_voltage_v        DOUBLE,
     pv2_voltage_v        DOUBLE,
     pv1_power_w          DOUBLE,
     pv2_power_w          DOUBLE,
     pv1_current_a        DOUBLE,
     pv2_current_a        DOUBLE,
     load_power_w         DOUBLE,
     ac_apparent_power_va DOUBLE,
     grid_power_w         DOUBLE,
     battery_voltage_v    DOUBLE,
     battery_power_w      DOUBLE,
     battery_current_a    DOUBLE,
     ac_input_voltage_v   DOUBLE,
     ac_output_voltage_v  DOUBLE,
     ac_input_current_a   DOUBLE,
     grid_frequency_hz    DOUBLE,
     temp_c               DOUBLE,
     energy_today_kwh     DOUBLE,
     energy_total_kwh     DOUBLE,
     pv_total_power_w     DOUBLE,
     PRIMARY KEY (device_serial, ts)
   )")

(def create-dead-letter-ddl
  "CREATE TABLE IF NOT EXISTS dead_letter_readings (
     received_at   TIMESTAMPTZ NOT NULL,
     ts            TIMESTAMPTZ,
     device_serial VARCHAR,
     site_id       VARCHAR,
     hardware_id   VARCHAR,
     reason        VARCHAR     NOT NULL,
     payload       BLOB,
     data          JSON
   )")
