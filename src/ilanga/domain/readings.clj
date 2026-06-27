(ns ilanga.domain.readings
  (:require [malli.core :as m]))
;; DOMAIN — query intent only. No engine, no files, no SQL, no next.jdbc.
;; The Readings protocol names the queries the domain wants to ask; their
;; DuckDB realization (SQL, table/type names, DDL) lives in the adapter
;; (ilanga.db). See ADR-035 (amends ADR-026): the query vocabulary is a port,
;; not SQL written in the domain. Mentioning a file or engine here would
;; couple the domain to that infrastructure.

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

(defn reading-identity
  "The identity of a Reading: (device-serial, timestamp). Matches the readings
   PRIMARY KEY and the idempotent-append conflict target (ADR-002 / TDD-02)."
  [reading]
  [(:reading/device-serial reading) (:reading/timestamp reading)])

(defprotocol Readings
  "Query intent for the readings time-series — semantics, not SQL.
   Identity:    (device-serial, ts) — see reading-identity.
   Idempotency: append of a row whose identity already exists is a no-op
                (the DuckDB realization uses ON CONFLICT DO NOTHING). A
                same-identity/differing-content row is a conflict to be
                surfaced to the caller (dead-letter, TDD-02 TODO) — never
                silently dropped.
   No method here names an engine, table, column type, or file. The adapter
   (ilanga.db/DuckDbReadings) is the realization; an in-memory fake can
   satisfy this protocol with no database at all."

  (latest   [this site-id]
    "Most-recent reading for site-id, across all devices at the site. Row or nil.")
  (in-range [this site-id from to]
    "Readings for site-id with ts ∈ [from, to), ascending. from/to are instants.")
  (append   [this reading]
    "Persist reading; idempotent on identical identity. Returns the adapter result."))