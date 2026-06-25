# ADR-027: Component Lifecycle

## Status
Proposed

## Problem
The application has multiple subsystems that depend on each other and must start in a specific order. Global config (device registry, hardware descriptors) must be accessible before the TCP server accepts connections. The config store must be open before the engine reads descriptors. On shutdown, live connections must drain before the stores are closed.

The lifecycle is genuinely **two-layered**, and conflating them is the core mistake this ADR must avoid:

- **App components — started once, in dependency order, at boot.** The config-store datasource, the DuckDB time-series datasource, the TCP/ingestion server, the engine/pipeline, and the `:tick` timer. These form the dependency graph a lifecycle library (Integrant/Mount/Component) wires and starts.
- **Per-session bindings — constructed on demand, not at boot.** A `TenantStore` is constructed by `open-store` once per device connection (ADR-020, after the serial lookup) or per LLM session — layered *over* the already-running app datasources. It is not a boot-time singleton.

This distinction is already decided by ADR-026/020: `open-store` is called per connection, and ADR-020 is explicit that no `TenantStore` is constructed before the serial lookup succeeds. So `open-store` is **not** a component in the app graph — it is called by the connection component, once per connection, over the app-level datasources that *are* components.

There is no decision on how the **app-component** layer's dependencies are expressed, wired, and managed at runtime.

## Why this must be decided
- **Startup order has real dependencies** — wrong order causes null reference failures or races at startup; these are hard to diagnose.
- **REPL workflow depends on it** — the chosen model determines whether a developer can reload a single subsystem without restarting the whole application. This matters daily during development.
- **Testability depends on it** — subsystems must be startable and stoppable in isolation for unit and integration tests. An untestable wiring model produces tests that either start everything or test nothing.
- **The choice is expensive to change** — the lifecycle model touches every subsystem's initialisation code; retrofitting a different model is a broad rewrite.

## Options to evaluate
- **Integrant** — data-driven; the system is described as an EDN config map; `ig/init` starts components in dependency order; `ig/halt!` stops them. REPL-friendly; `integrant.repl` for interactive development.
- **Mount** — `defstate` macros declare state with start/stop fns; dependencies are implicit (load order). Simpler but less explicit about dependency graph.
- **Stuart Sierra's Component** — `defrecord` components with `start`/`stop`; explicit dependency injection via `using`. More verbose than Integrant.
- **Plain namespaces + atoms** — no library; manual startup functions and `defonce` atoms. Simple, but startup order and teardown are ad-hoc and hard to test.

## Open questions
- How are subsystem configs (port numbers, file paths) passed in — EDN file, environment variables, or config store?
- How does the REPL workflow interact with live inverter connections?

**Already resolved (recorded here, not open):**
- *How does `open-store` fit into the lifecycle — is it a component, or called by a component?* → Called by the connection component, once per connection, over the app-level datasources (ADR-020/026). Not a component in the app graph.
- *Config bootstrap chicken-and-egg.* → The config store is itself an app component, so it needs its own parameters (path to `config.db`, listen port) *before* it exists. A small bootstrap layer below the config store — EDN file or environment variables for the handful of parameters needed before any store is open — is the root of the root. ADR-026's "global config is reachable before any store exists" bottoms out here: the bootstrap config is what makes the config store itself reachable.

## Decision
TODO
