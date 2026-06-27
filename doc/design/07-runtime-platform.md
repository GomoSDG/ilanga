# 07 — Runtime Platform

**Status:** In progress — the lifecycle mechanism and boot datasources are flushed and built (Integrant, `:ilanga/config-ds`, `:ilanga/duckdb-pool`, `bootstrap.edn`, `open-store` two-layer fix). The thread surface, `:tick` driver, and observability remain TODO (deferred to the Aleph and engine chunks that exercise them, per ADR-027).

## Purpose & scope
The platform layer the domain subsystems run *on*: how app components start in dependency order and tear down cleanly, how work is dispatched across threads and backpressure is propagated, and how the whole flow is observed. This is the cross-cutting runtime that every domain TDD (01–06) assumes but none owns. **Excludes** domain logic — the platform is blind to solar; it provides the component graph, the thread model, and the observability surface that domain code runs inside.

The lifecycle is two-layered (ADR-027): **app components** (config-store datasource, DuckDB datasource, TCP/ingestion server, engine/pipeline, `:tick` timer) start once in dependency order; **per-session bindings** (`TenantStore` via `open-store`) are constructed on demand over those app datasources. This TDD owns the first layer; the second is owned by 02 (the `open-store` construction) and 01 (the connection lifecycle that calls it).

## Governing ADRs
- ADR-027 Component lifecycle — **Accepted** (Integrant, boot-only)
- ADR-028 Concurrency model — Proposed (lands with the Aleph chunk)
- ADR-029 Observability and logging — Proposed (lands with the first real runtime)
- ADR-007 Declarative pipeline (`:tick` signal) — Accepted
- ADR-018 Ingestion TCP server (Aleph/Netty) — Accepted
- ADR-008 DuckDB + SQLite (datasource components) — Accepted

## Interfaces
The component graph is an **Integrant** EDN map (`ilanga.system/config`), started by `ig/init` in dependency order and halted by `ig/halt!` in reverse. Each key has an `ig/init-key` and `ig/halt-key!` method. This slice wires two app-datasource components:

- `:ilanga/config-ds` → `ilanga.db/open-config-ds [path]` (the single SQLite config store). Halt is a no-op: next.jdbc's URL datasource is a connection factory, not a held-open resource — each `execute!` opens/closes its own connection, so there is nothing to close.
- `:ilanga/duckdb-pool` → `ilanga.db/->duckdb-pool [dir]`, a `DuckDbPool [dir cache]` that opens one DuckDB datasource per tenant lazily (`get-or-open`) and caches it for the app's life. Halt (`close-all`) clears the cache; the cached entries are likewise connection factories, not held-open connections.

`ilanga.system/app` projects the started system into the plain `{:config-ds :duckdb-pool}` map that `open-store` takes, so the adapter and `open-store` are not coupled to Integrant keys or the lifecycle lib. `open-store` is **not** a graph component — it is called per-connection over these boot-started datasources (ADR-020/026). The DuckDB datasource is opened once per tenant and reused across connections, not re-opened per `open-store` call: that is the two-layer fix.

Deferred to later chunks:
- **The thread surface** (Netty I/O vs `core.async/thread` vs `go` vs write threads) — ADR-028, lands with the Aleph chunk.
- **The `:tick` driver** — what emits `:tick` (ADR-007) and on what thread — lands with the engine (TDD-03).
- **The observability surface** (log library, structured format, correlation-ID, metrics) — ADR-029, lands with the first real runtime.

## Data structures / schemas
- **Component graph** — Integrant EDN map, app-component layer only (`TenantStore` is *not* in it):
  ```clojure
  {:ilanga/config-ds   {:path (:config-db bootstrap)}
   :ilanga/duckdb-pool {:dir  (:duckdb-dir bootstrap)}}
  ```
- **Bootstrap config** — `resources/bootstrap.edn`, the root of the root (ADR-027): `{:listen-port :duckdb-dir :config-db}`. Loaded by `ilanga.system/bootstrap`, overridable via `ILANGA_LISTEN_PORT` / `ILANGA_DUCKDB_DIR` / `ILANGA_CONFIG_DB`. (`:listen-port` is carried now but unused until the Aleph chunk.)
- **Structured log record / correlation context** — TODO (ADR-029).
- **Log-vs-audit-trail boundary** — config mutation → `config_history` (ADR-005/013); runtime event → log. TODO with ADR-029.

## Sequences / flows
- **Startup (this slice):** `bootstrap` (read `bootstrap.edn` + env overrides) → `ig/init` the graph → `:ilanga/config-ds` and `:ilanga/duckdb-pool` started → ready. Full startup (engine, `:tick`, TCP) is the later chunks.
- **Shutdown (this slice):** `ig/halt!` → `:ilanga/duckdb-pool` (`close-all`, clears cache) and `:ilanga/config-ds` (no-op), reverse order. Full shutdown (drain connections, halt engine/`:tick`, close datasources) lands with the Aleph chunk.
- **Per-connection** — accept on Netty thread → serial lookup → `(open-store app tenant-id)` over the pooled datasource → `TenantStore` bound to the stream → normal processing. TODO: the accept/serial-lookup/binding steps land with TDD-01; `open-store` is already the two-layer seam.
- **A reading's journey** via correlation context — TODO (ADR-029).

## Invariants & error modes
TODO:
- App components start in dependency order; a component's dependencies are started before it, halted after it.
- No blocking JDBC (write *or* datasource-open) on Netty I/O threads or in `go` blocks (ADR-028).
- The TCP server does not accept connections until its dependencies (config store, datasources) are up — connections arriving earlier are refused, not lost-and-raced.
- Live connections drain before stores close on shutdown — no mid-drain datasource teardown.
- Silent failures are observable: a dropped CRC-failed packet or a KPI that becomes unavailable is logged, not swallowed (ADR-029).
- Config mutation is audited in `config_history`; it does not also need to be a log line, and vice versa — the two surfaces do not duplicate.

## Open / deferred
- **Lifecycle lib — decided:** Integrant (ADR-027 Accepted). Thread model (ADR-028) and logging lib (ADR-029) still `Decision: TODO`, landing with their chunks.
- Metrics surface (counters/gauges) beyond logs — open in ADR-029.
- Backpressure policy on the ingestion→engine channel — open in ADR-028.
- REPL workflow interaction with live inverter connections — open, resolves with the Aleph chunk.