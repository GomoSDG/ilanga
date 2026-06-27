(ns ilanga.domain.store)

;; Binding record carrying already-bound, already-tenant-scoped clients
;; (ADR-026). Per ADR-035, the time-series clients are domain-defined ports
;; (Readings, later Days/…) realized by the adapter; domain code calls the
;; port, never a datasource. It never reads :tenant-id; it filters rows by
;; site-id and device-serial.
(defrecord TenantStore
  [tenant-id   ; string — internal: config wrapper, cloud session var, export stamp
   readings    ; Readings port impl (ilanga.db/DuckDbReadings) — readings queries
   config])    ; ConfigClient (scoped wrapper, injects tenant-id into section keys)