(ns ilanga.domain.store)

;; Binding record, not an interface (ADR-026).
;; Domain code receives a TenantStore and uses its clients directly.
;; It never reads :tenant-id; it filters rows by site-id and device-serial.
(defrecord TenantStore
  [tenant-id    ; string — internal: config wrapper, cloud session var, export stamp
   time-series  ; DuckDB connectable — readings, days, periods, incidents (rows carry site_id)
   config])     ; ConfigClient (scoped wrapper, injects tenant-id into section keys)
