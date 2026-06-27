(ns ilanga.db
  (:require [next.jdbc :as jdbc]
            [ilanga.domain.store :as store]
            [ilanga.domain.readings :as readings])
  (:import [java.sql Timestamp]
           [java.time Instant]))
;; ADAPTER — the DuckDB realization of the domain's Readings port (ADR-035).
;; SQL, table/type names, DDL, and file paths live here, never in the domain.
;; open-store is the only construction point for a TenantStore (ADR-026).
;; When a second entity's SQL lands, split per-entity: ilanga.db.readings, etc.
;; (ADR-030 growth note); open-store stays the one assembly point.

(defn- duckdb-ds [tenant-id]
  ;; File-per-tenant path shape; single tenant today uses data/home.ddb.
  (jdbc/get-datasource (str "jdbc:duckdb:data/" tenant-id ".ddb")))

(defn- sqlite-ds []
  (jdbc/get-datasource "jdbc:sqlite:data/config.db"))

;; SQLite config client — injects tenant-id into section-key lookups so domain
;; code never types or sees tenant-id when reading config (ADR-026).
(defrecord ConfigClient [ds tenant-id])

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

(defn- row->reading [row]
  ;; Inverse of reading->row: snake_case columns + jdbc types -> namespaced
  ;; Reading + Instants. NULL columns are dropped (absent key) so the result
  ;; matches the optional-field domain model and round-trips through valid?.
  (when row
    (into {} (filter (comp some? val))
          {:reading/timestamp         (Instant/from (:ts row))
           :reading/seq               (:seq row)
           :reading/device-serial     (:device_serial row)
           :reading/site-id           (:site_id row)
           :reading/hardware-id       (keyword (:hardware_id row))
           :reading/received-at       (Instant/from (:received_at row))
           :reading/pv1-voltage-v     (:pv1_voltage_v row)
           :reading/pv2-voltage-v     (:pv2_voltage_v row)
           :reading/pv1-power-w       (:pv1_power_w row)
           :reading/pv2-power-w       (:pv2_power_w row)
           :reading/pv1-current-a     (:pv1_current_a row)
           :reading/pv2-current-a     (:pv2_current_a row)
           :reading/load-power-w      (:load_power_w row)
           :reading/ac-apparent-power-va (:ac_apparent_power_va row)
           :reading/grid-power-w      (:grid_power_w row)
           :reading/battery-voltage-v (:battery_voltage_v row)
           :reading/battery-power-w   (:battery_power_w row)
           :reading/battery-current-a (:battery_current_a row)
           :reading/ac-input-voltage-v  (:ac_input_voltage_v row)
           :reading/ac-output-voltage-v (:ac_output_voltage_v row)
           :reading/ac-input-current-a  (:ac_input_current_a row)
           :reading/grid-frequency-hz   (:grid_frequency_hz row)
           :reading/temp-c            (:temp_c row)
           :reading/energy-today-kwh  (:energy_today_kwh row)
           :reading/energy-total-kwh  (:energy_total_kwh row)
           :reading/pv-total-power-w  (:pv_total_power_w row)})))

(defrecord DuckDbReadings [ds]
  readings/Readings
  (latest [_ site-id]
    (row->reading
     (jdbc/execute-one!
      ds
      ["SELECT * FROM readings
         WHERE site_id = ?
         ORDER BY ts DESC LIMIT 1" site-id])))
  (in-range [_ site-id from to]
    (mapv row->reading
          (jdbc/execute!
           ds
           ["SELECT * FROM readings
              WHERE site_id = ?
                AND ts >= CAST(? AS TIMESTAMPTZ)
                AND ts <  CAST(? AS TIMESTAMPTZ)
              ORDER BY ts ASC" site-id from to])))
  (append [_ reading]
    (let [row (reading->row reading)]
      (jdbc/execute-one!
       ds
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
        (:pv_total_power_w row)]))))

(def readings-ddl
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

(def dead-letter-ddl
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

(defn ensure-schema!
  "Create tables if they do not exist. Safe to call on every startup.
   DDL lives in the adapter — it names the engine (ADR-035)."
  [tenant-store]
  (let [ds (:ds (:readings tenant-store))]
    (jdbc/execute! ds [readings-ddl])
    (jdbc/execute! ds [dead-letter-ddl])))

(defn open-store
  "Construct a TenantStore for tenant-id.
   Called once per session/connection at establishment — not at boot (ADR-027).
   The :readings client is a DuckDbReadings (the Readings port realization)."
  [{:keys [tenant-id]}]
  (let [ds (duckdb-ds tenant-id)]
    (store/->TenantStore
     tenant-id
     (->DuckDbReadings ds)
     (->ConfigClient (sqlite-ds) tenant-id))))