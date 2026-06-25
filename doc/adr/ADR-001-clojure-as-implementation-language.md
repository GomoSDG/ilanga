# ADR-001: Clojure as Implementation Language

## Status
Accepted

## Context
Building a solar monitoring system with a declarative, data-first architecture. The system models energy flows, computes KPIs over time-series data, interprets declarative config, and integrates with LLMs via MCP and a chat API. The language choice shapes the entire design.

## Options Considered
- **Python** — strong data ecosystem, but mutable-first, weaker concurrency model, no native EDN
- **TypeScript** — broad ecosystem, but object-oriented tendencies work against data-first design
- **Kotlin** — good JVM language, functional capable, but not data-first by nature
- **Clojure** — data-first, functional, homoiconic, immutable by default, JVM ecosystem

## Decision
Clojure on the JVM.

## Rationale
The entire architecture is data-first. Registries, config store, entities, KPI descriptors, action descriptors, and pipeline rules are all maps. Clojure is built for this.

- **Homoiconic**: KPI formulas like `[:div :day/energy-kwh :site/installed-kwp]` are just Clojure data — no parser or separate DSL runtime needed
- **Immutable by default**: aligns with append-only time-series and event-sourced config
- **Namespace-qualified keywords** (`:day/energy-kwh`, `:kpi/value`) make data self-describing without external schemas
- **`defmulti`** is a natural fit for dispatching on `:charge/type`, `:signal/type`, `:kpi/combine`
- **REPL-driven development** enables interactive exploration of solar data — querying the DB, interpreting results, and building understanding incrementally without a full compile cycle
- **Libraries**: `next.jdbc`, `Malli`, `honey.sql`, `core.async` all first-class

## Consequences
- (+) Natural fit for the declarative architecture — config, registry, and entities are all just maps
- (+) REPL enables interactive data exploration during development and debugging
- (+) EDN as native config format — no serialisation layer between code and config files
- (+) Strong concurrency primitives for pipeline signal handling
- (-) Smaller talent pool than Python or TypeScript
- (-) JVM startup time — mitigated by long-running process model on home server
- (-) Learning curve for developers unfamiliar with functional/Lisp style
