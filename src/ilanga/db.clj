(ns ilanga.db
  (:require [next.jdbc :as jdbc]
            [ilanga.domain.store :as store]
            [ilanga.domain.readings :as readings]))
;; INFRA — meta layer. The only place datasources are constructed.
;; open-store is the only construction point for TenantStore (ADR-026).

;; SQLite config client — injects tenant-id into section-key lookups so domain
;; code never types or sees tenant-id when reading config (ADR-026).
(defrecord ConfigClient [ds tenant-id])

(defn- duckdb-ds [tenant-id]
  ;; File-per-tenant path shape; single tenant today uses data/home.ddb.
  (jdbc/get-datasource (str "jdbc:duckdb:data/" tenant-id ".ddb")))

(defn- sqlite-ds []
  (jdbc/get-datasource "jdbc:sqlite:data/config.db"))

(defn ensure-schema!
  "Create tables if they do not exist. Safe to call on every startup."
  [tenant-store]
  (jdbc/execute! (:time-series tenant-store) [readings/create-table-ddl])
  (jdbc/execute! (:time-series tenant-store) [readings/create-dead-letter-ddl]))

(defn open-store
  "Construct a TenantStore for tenant-id.
   Called once per session/connection at establishment — not at boot (ADR-027)."
  [{:keys [tenant-id]}]
  (store/->TenantStore
   tenant-id
   (duckdb-ds tenant-id)
   (->ConfigClient (sqlite-ds) tenant-id)))
