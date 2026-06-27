(ns ilanga.ingest
  (:require [ilanga.domain.readings :as readings]))
;; ORCHESTRATION — the ingest use case. Knows the Readings port only; names no
;; engine, table, or file. This is the home of the stamp → validate → append →
;; dead-letter flow (TDD-01); it sits above the domain port and the adapter
;; realization. Testable with an in-memory fake Readings — no DB, no with-redefs.

(defn ingest-reading
  "Validate and append one decoded Reading through the Readings port.
   TODO (TDD-02): detect a same-identity/differing-content append and dead-letter
   it instead of relying on the adapter's ON CONFLICT DO NOTHING, which today
   treats every same-identity append as a no-op replay."
  [readings-port reading]
  {:pre [(readings/valid? reading)]}
  (readings/append readings-port reading))