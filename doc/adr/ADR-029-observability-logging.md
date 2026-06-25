# ADR-029: Observability and Logging

## Status
Proposed

## Problem
The application has a complex, multi-subsystem data flow — TCP ingestion, protocol decoding, DuckDB writes, signal dispatch, KPI computation, LLM calls, config store writes. When something goes wrong (a CRC failure, a missed `:day-complete`, an LLM authoring an invalid descriptor, a DuckDB write error), there is no stated strategy for how it is detected, recorded, and diagnosed.

No logging library has been chosen. No structured logging format has been defined. No decision exists on what gets instrumented, at what level, or how logs from different subsystems are correlated.

## Why this must be decided
- **Library choice affects every subsystem** — log calls appear throughout the codebase; changing the library later requires touching every call site.
- **Structured vs unstructured affects queryability** — a plain string log is human-readable but unsearchable at scale; structured maps (JSON/EDN) are queryable but require a consistent schema agreement upfront.
- **Correlation across subsystems is non-trivial** — a single inverter packet touches ingestion, persistence, and the engine pipeline; tracing it requires a correlation ID strategy decided before the first line is logged.
- **The LLM surface and engine produce events that must be auditable** — descriptor writes, KPI computation failures, and context package rejections need a record beyond what a generic log line provides; the boundary between logging and the config audit trail (ADR-005/013) must be stated.
- **Silent failures are the worst failures** — a CRC-failed packet that is silently dropped, or a KPI that silently becomes unavailable, must be observable without querying the database.

## Concerns to address
- Library choice: Timbre (Clojure-native, structured, tap-friendly) vs clojure.tools.logging (SLF4J bridge, standard Java ecosystem) vs other.
- Structured log format — EDN, JSON, or both?
- Correlation ID — how does a reading's journey from TCP packet to KPI get traced?
- Log levels and what belongs at each level across subsystems.
- Boundary with the config audit trail — what goes in logs vs what goes in `config_history`.
- Metrics — is there a metrics surface (counters, gauges) beyond logs, or is logging the sole observability mechanism?

## Decision
TODO
