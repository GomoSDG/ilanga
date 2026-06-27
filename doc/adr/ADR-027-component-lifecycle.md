# ADR-027: Component Lifecycle

## Status
Accepted

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
- How does the REPL workflow interact with live inverter connections? → resolved when the TCP/Aleph component lands (ADR-028 chunk).

**Already resolved (recorded here, not open):**
- *How does `open-store` fit into the lifecycle — is it a component, or called by a component?* → Called by the connection component, once per connection, over the app-level datasources (ADR-020/026). Not a component in the app graph. `ilanga.db/open-store` takes the app datasources (`app = {:config-ds :duckdb-pool}`) and assembles a `TenantStore` over them — it constructs nothing of its own.
- *Config bootstrap chicken-and-egg.* → The config store is itself an app component, so it needs its own parameters (path to `config.db`, listen port) *before* it exists. A small bootstrap layer below the config store — `resources/bootstrap.edn` + `ILANGA_*` env overrides — is the root of the root. ADR-026's "global config is reachable before any store exists" bottoms out here: the bootstrap config is what makes the config store itself reachable.
- *How are subsystem configs passed in?* → EDN file (`bootstrap.edn`) for the root-of-the-root, overridable by `ILANGA_*` env vars; everything else lives in the config store once it exists.

## Decision
**Integrant**, boot-only scope for this slice.

The component graph is an EDN data map (`ilanga.system/config`), started by `ig/init` in dependency order and halted by `ig/halt!`. This slice wires only the two app datasources the next chunk depends on: `:ilanga/config-ds` (SQLite) and `:ilanga/duckdb-pool` (a tenant→datasource pool). `ilanga.system/app` projects the started system into the plain `{:config-ds :duckdb-pool}` map that `open-store` takes, so the adapter is not coupled to Integrant keys.

Rationale:
- **Data-as-graph matches the descriptors-as-data ethos (ADR-005).** The system is an EDN map, not code; components and their dependencies are inspectable and reloadable as data.
- **Explicit, inspectable dependency order.** `ig/init` derives order from the graph; there is no implicit load order to misread. This is the disqualifier for **Mount** — its `defstate` load order is implicit and a footgun in a system with real start-order dependencies.
- **REPL payoff.** `integrant.repl/reset` re-runs the graph interactively, the daily-development win.
- **Component** was rejected as graph-in-code and verbose relative to Integrant's data map; **plain namespaces + atoms** as ad-hoc and hard to test.

**`open-store` is not a graph component** (restated, because it is the seam the two-layer model turns on): it is called per-connection by the future TCP component over the boot-started datasources. The DuckDB datasource is opened lazily and cached by `DuckDbPool` so it is constructed once per tenant and reused across connections, not re-opened on every `open-store` call — that is the two-layer fix.

Deferred to named later chunks (same "don't decide before evidence" principle as ADR-032): the **thread model** (ADR-028, lands with the Aleph/runtime chunk), **observability** (ADR-029, lands with the first real runtime), and the **`:tick`** driver (lands with the engine, TDD-03). They stay Proposed/TODO until their code exercises them.
