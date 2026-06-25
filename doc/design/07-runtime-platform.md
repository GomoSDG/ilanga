# 07 — Runtime Platform

**Status:** Stub — skeleton only; implementation detail pending.

## Purpose & scope
The platform layer the domain subsystems run *on*: how app components start in dependency order and tear down cleanly, how work is dispatched across threads and backpressure is propagated, and how the whole flow is observed. This is the cross-cutting runtime that every domain TDD (01–06) assumes but none owns. **Excludes** domain logic — the platform is blind to solar; it provides the component graph, the thread model, and the observability surface that domain code runs inside.

The lifecycle is two-layered (ADR-027): **app components** (config-store datasource, DuckDB datasource, TCP/ingestion server, engine/pipeline, `:tick` timer) start once in dependency order; **per-session bindings** (`TenantStore` via `open-store`) are constructed on demand over those app datasources. This TDD owns the first layer; the second is owned by 02 (the `open-store` construction) and 01 (the connection lifecycle that calls it).

## Governing ADRs
- ADR-027 Component lifecycle — Proposed
- ADR-028 Concurrency model — Proposed
- ADR-029 Observability and logging — Proposed
- ADR-007 Declarative pipeline (`:tick` signal) — Accepted
- ADR-018 Ingestion TCP server (Aleph/Netty) — Accepted
- ADR-008 DuckDB + SQLite (datasource components) — Accepted

## Interfaces
TODO:
- The component graph: how app components are declared, started in dependency order, and halted in reverse. Whether a lifecycle library (Integrant/Mount/Component) is used and how the graph is described (ADR-027 decision pending).
- The thread surface: Netty I/O threads (non-blocking only), `core.async/thread` (blocking allowed), `go` blocks (non-blocking only), and any dedicated write/pipeline threads — which operations land where (ADR-028 decision pending).
- The `:tick` driver: what emits the `:tick` signal (ADR-007) and on what thread.
- The observability surface: the logging library, structured log format, correlation-ID threading, log levels, and the metrics surface if any (ADR-029 decision pending).

## Data structures / schemas
TODO:
- Component graph description (EDN map if Integrant; defstate if Mount; records if Component) — the app-component layer only; `TenantStore` is not in it.
- Bootstrap config: the small set of parameters needed before the config store exists (config-store path, listen port, DuckDB path) — EDN file or env vars; the root of the root (ADR-027).
- Structured log record schema (EDN/JSON) and the correlation-context shape threaded from connection/reading through to KPI.
- The log-vs-audit-trail boundary: config mutation → `config_history` (ADR-005/013); runtime event → log.

## Sequences / flows
TODO:
- Startup: bootstrap config → config-store datasource → DuckDB datasource → engine/pipeline → `:tick` timer → TCP server (accepts connections only after its dependencies are up) → ready.
- Per-connection: accept on Netty thread → serial lookup → `open-store` (offloaded or over pooled datasource, ADR-028) → `TenantStore` bound to stream (tenant-scoped; site-id carried for reading stamping) → normal processing.
- Shutdown: stop accepting connections → drain live connections → halt engine/pipeline + `:tick` → close datasources (reverse dependency order).
- A reading's journey traced end-to-end via correlation context: packet → decode → write → signal → KPI (ADR-029).

## Invariants & error modes
TODO:
- App components start in dependency order; a component's dependencies are started before it, halted after it.
- No blocking JDBC (write *or* datasource-open) on Netty I/O threads or in `go` blocks (ADR-028).
- The TCP server does not accept connections until its dependencies (config store, datasources) are up — connections arriving earlier are refused, not lost-and-raced.
- Live connections drain before stores close on shutdown — no mid-drain datasource teardown.
- Silent failures are observable: a dropped CRC-failed packet or a KPI that becomes unavailable is logged, not swallowed (ADR-029).
- Config mutation is audited in `config_history`; it does not also need to be a log line, and vice versa — the two surfaces do not duplicate.

## Open / deferred
- The three library/mechanism choices (lifecycle lib, thread model, logging lib) — ADR-027/028/029 `Decision: TODO`.
- Metrics surface (counters/gauges) beyond logs — open in ADR-029.
- Backpressure policy on the ingestion→engine channel — open in ADR-028.
- REPL workflow interaction with live inverter connections — open in ADR-027.