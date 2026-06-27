# ADR-035: Persistence Port — Query Intent in Domain, Realization in Adapter

## Status
Accepted — amends ADR-026 (the `TenantStore` "binding record, not an interface / domain writes queries" clauses) and ADR-030 (the domain-purity / lint clause).

## Context
ADR-026 specifies that all data access goes through a `TenantStore` — "a binding record, not an interface … it has no query methods. Domain namespaces are the repositories; they receive a store and use its clients directly, writing queries that filter by `site_id`." The first implementation put that literally: `ilanga.domain.readings` required `next.jdbc` and contained the SQL — `SELECT … FROM readings WHERE site_id = ?`, `ON CONFLICT (device_serial, ts) DO NOTHING`, `CAST(? AS TIMESTAMPTZ)`.

That SQL is domain logic — it encodes the query intent (latest reading for a site; readings with `ts ∈ [from, to)`; append idempotent on `(device-serial, ts)`). But it also names the engine: the `readings` table, `TIMESTAMPTZ`/`VARCHAR` column types, `ON CONFLICT`, and (via `next.jdbc` and `data/<tenant>.ddb`) the DuckDB file. The moment the domain names a file or an engine, it is coupled to that infrastructure. Relocating the SQL to `ilanga.db` does not make it "not domain" — the *query* is still domain — but it draws the line in the right place: **the query is domain; the engine the query runs on is not.**

The testability pressure made the coupling concrete. To exercise the domain's read/write path without a real DuckDB, you would need `with-redefs` to stub `next.jdbc`, or spin up a database. DuckDB file-per-tenant is cheap to spin up (the replay test does it), so the SQL *realization* can be integration-tested against a throwaway `.ddb` — but the *domain decision logic* (validate, decide identity conflict, dead-letter) should not need a database at all.

## Decision
The persistence query vocabulary is a **port** (a `defprotocol`) defined in the domain; the adapter holds the SQL realization.

- **`ilanga.domain.readings`** defines the `Readings` protocol — `latest`, `in-range`, `append` — as query *intent* with its semantics (identity, idempotency, range/ordering) in docstrings. It also holds the `Reading` schema, `valid?`, and `reading-identity`. **No `next.jdbc`, no SQL, no table/type name, no file path.** Anything that names an engine lives in the adapter.
- **`ilanga.ingest`** is the use-case layer above the port: `ingest-reading [readings-port reading]` validates and appends through the port, and is where the stamp → validate → append → dead-letter flow (TDD-01/TDD-02) lives. It names no engine; it talks to the port.
- **`ilanga.db`** is the realization: `DuckDbReadings` implements `Readings` with SQL, plus the DDL, `reading->row` (the `java.sql.Timestamp` binding is engine-coupled), `open-store`, and `ensure-schema!`. SQL strings, `TIMESTAMPTZ`, `ON CONFLICT`, and `data/<tenant>.ddb` all live here.

`TenantStore` carries the port impls, not raw datasources:

```clojure
(defrecord TenantStore [tenant-id readings config])
;; readings = a Readings impl (ilanga.db/DuckDbReadings); config = ConfigClient.
```

`open-store` is still the only construction point (ADR-026); it builds the `DuckDbReadings` (holding the datasource internally) and carries it as `:readings`. Call sites thread the port: `(readings/latest (:readings store) site-id)`, `(ingest/ingest-reading (:readings store) reading)`.

The seam is **intent ↔ realization**, not "domain ↔ infra plumbing." `ilanga.db` genuinely contains domain logic — written in SQL and bound to DuckDB. The port lets that realization be swapped (a future `PostgresReadings` behind the same protocol) and, more importantly, keeps engine names out of the domain so the domain and the use-case layer are testable with an in-memory `reify` of `Readings` — no database, no `with-redefs`.

ADR-030's lint rule is tightened: `ilanga.domain.*` may not require `next.jdbc` (or any SQL/JDBC library) and may not name a storage engine; the SQL realization belongs in `ilanga.*` adapters.

## Rationale
- The query is domain; the engine is not. Putting SQL in the domain couples the domain to DuckDB the moment it names a table or type. The port is the boundary that keeps engine names in the adapter.
- Testability without `with-redefs`: the domain decision logic and the ingest use case run against an in-memory fake `Readings`. Only the SQL realization needs a database, and that is correctly an adapter integration test (the replay test), not a domain test.
- It honors ADR-026's load-bearing property — tenant scoping encapsulated in the client, domain never sees `tenant_id`, filters `site_id` — unchanged. The store still carries already-bound, already-tenant-scoped clients; those clients are now port impls rather than raw datasources.
- A second storage engine is not a near-term goal, but the port is earned by the decoupling and testability alone; engine-swap is a free side benefit, not the justification.

## Consequences
- (+) Domain and `ilanga.ingest` contain no engine/file names and are unit-testable with fakes; the replay test is reframed as an adapter integration test (files named there are correct).
- (+) The intent (protocol contract) and the realization (SQL) are separately readable; the contract does not depend on DuckDB specifics.
- (+) Engine swap is a new record behind the same protocol; `open-store` is the only thing that changes.
- (-) `TenantStore` is now an interface for the query vocabulary (it carries port impls), superseding ADR-026's "binding record, not an interface … no query methods." The binding-record property (one construction point, tenant-scoped clients, domain never sees `tenant_id`) is preserved; the "no query methods" property is deliberately dropped.
- (-) One protocol method per query; adding a query is a protocol change plus an adapter impl. The vocabulary is bounded by what the dashboard/engine/ingest actually ask, so this is not a burden at single-home scale.
- (-) `ilanga.db` will grow as entities are added (days, periods, incidents, …). It splits per-entity into `ilanga.db.readings`, `ilanga.db.config`, etc. when a second entity's SQL lands; `open-store` stays the single assembly point. Noted as a deliberate growth event in ADR-030, not a drift.

## Alternatives considered
- **Keep SQL in `ilanga.domain.readings`; integration-test against a throwaway DuckDB.** Rejected: it leaves engine names in the domain (the coupling this ADR exists to remove) and forces the domain's decision logic to be tested either with a database or with `with-redefs`. The cheap-DuckDB argument supports integration-testing the *realization*; it does not justify putting the realization in the domain.
- **Store carries raw datasources; protocol impl extends `TenantStore` in the adapter.** Rejected: the store would still expose a raw `:time-series` datasource the domain *could* call directly, so the "domain takes protocols only" property would rely on discipline rather than structure. Carrying the port impl as `:readings` makes the domain's only data-access surface the protocol itself.