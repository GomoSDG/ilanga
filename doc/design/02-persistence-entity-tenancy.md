# 02 — Persistence, Entity Model & Tenancy

**Status:** Stub — skeleton only; implementation detail pending.

## Purpose & scope
How facts are stored and partitioned: the eleven domain entities, the in-process DuckDB (time-series) + SQLite JSON (config) choice, and the `TenantStore` binding whose clients encapsulate tenant scoping — making the local single-tenant file a pre-shaped cloud shard. The load-bearing abstraction is `TenantStore`; every data access goes through it. **Excludes** config-store internals and permissions (05) and the engine's use of entities (03).

## Governing ADRs
- ADR-002 Domain entity model (11 entities) — Accepted
- ADR-008 DuckDB + SQLite initial persistence — Accepted
- ADR-026 Multi-tenant storage model — Accepted

## Interfaces

### `TenantStore` & `open-store` — the one data-access surface

A `TenantStore` is a **binding record, not an interface** (ADR-026): it carries already-bound, already-tenant-scoped clients and has no query methods. Domain namespaces receive a store and use its clients directly, writing queries that filter by `site_id` (and `device-serial`) — never by `tenant_id`. They never read `(:tenant-id store)`.

```clojure
(defrecord TenantStore
  [tenant-id     ;; string — the tenant (owner) this store is bound to (internal use only)
   time-series    ;; DuckDB datasource (next.jdbc) — readings, days, periods, incidents (rows carry site_id)
   config])       ;; SQLite datasource (next.jdbc, scoped) — per-tenant tariffs, rules, dashboards
```

**`open-store` is the only construction point.** Nothing else constructs datasources; nothing else builds a `TenantStore`.

```clojure
(defn open-store [{:keys [tenant-id]}] ...)
;; => #solar.persistence.TenantStore{:tenant-id "home" :time-series <ds> :config <ds>}
```

- Input: the `tenant-id`, already resolved — from the device registry for a connection (ADR-020, alongside `site-id`), from the permission-id for an LLM session (ADR-013). `open-store` does not read the registry itself. `site-id` is *not* passed to `open-store` — it is a per-query filter and a stamp on each Reading, not an isolation key.
- Returns a `TenantStore` with both clients constructed and bound.
- Called **once per session/connection at establishment**, not at boot (ADR-027 two-layer lifecycle). The app-level datasources (the single DuckDB file, the single SQLite file) are started once by the runtime (TDD-07); `open-store` layers a tenant-scoped binding over them.

**How the clients get tenant-scoped (the encapsulation, per ADR-026):**

| Mode | time-series scoping | config scoping |
|---|---|---|
| Single-file single-tenant (start) | trivial — one file, one tenant; an unscoped-by-tenant query is already tenant-scoped. The file holds multiple sites' rows, distinguished by `site_id` (domain filters it) | section key encodes the tenant (`tariffs/home`) — the config client injects `tenant-id` |
| File-per-tenant (2nd tenant) | file path = `data/{tenant-id}.ddb`; the client physically cannot reach another tenant. Each file holds that tenant's sites via `site_id` | section key |
| Cloud (Postgres + RLS) | `SET app.tenant_id = …`; RLS policies on `tenant_id` enforce. `site_id` is an ordinary column the domain filters | same DB mechanism |

The asymmetry is intentional: **tenant scoping is structural** (file boundary / single-tenant invariant / RLS) — never a query-rewrite, because a query-rewrite is bypassable. A single DuckDB file holding multiple *tenants* is *not* a supported mode (DuckDB has no native row-level isolation — ADR-008); a single file holding multiple *sites of one tenant* **is** supported, via the `site_id` column. **Config scoping is by section key** — a code-enforced boundary, accepted because config is read-mostly and low leak-risk (ADR-026).

**The config client is a scoped wrapper.** SQLite holds all tenants' config in one file, so `open-store` returns a thin wrapper (not the raw datasource) that knows `tenant-id` and prefixes section keys. Domain config namespaces call it with a *logical* section name (`solar.tariffs/active` reads `:tariffs`); the wrapper maps `:tariffs` → `"tariffs/home"`. Domain code never sees or types `tenant-id`. *(Proposed mechanism — ADR-026 mandates "client encapsulates scoping"; the wrapper is how TDD-02 satisfies it for SQLite. Redirectable.)*

**`tenant-id` is on the record for internal use only** — the config wrapper, the cloud session var, and export stamping read it; domain code does not. The structural tenant scoping means domain code has no *need* to filter on it (it filters `site_id` instead); the config wrapper means it has no *need* to scope with it. Not reading it is a discipline, backed by structure where structure is available.

**Global config is not behind a `TenantStore`.** Device registry, permission descriptors, prompt templates, and hardware descriptors are global — read without a store, before `open-store` is ever called (ADR-026). Global config access is a separate surface owned by TDD-05.

**Datasources** — both via `next.jdbc` (same library, different JDBC drivers, ADR-008): DuckDB `{:dbtype "duckdb" :dbname "data/solar.ddb"}`, SQLite `{:dbtype "sqlite" :dbname "data/config.db"}`.

### Domain query vocabulary
TODO (next sub-step): the `solar.readings/*` read/write functions — `latest store site-id`, `in-range store site-id from to`, `write! store reading` — the read API the dashboard (TDD-08) calls. Queries take `site-id` explicitly; `tenant_id` is never an argument. Per-domain vocabularies for the other entities are defined alongside TDD-03.

### Config split (global vs per-tenant)
- **Global** (no `tenant_id`, read without a store): device registry, permission descriptors, prompt templates, hardware descriptors.
- **Per-tenant** (scoped by section key): tariffs, rules, dashboards, site config, load-shedding area. Within a tenant, per-site where site-specific.

## Data structures / schemas

### Entity schemas

Each entity is a namespaced-keyword map, Malli-validated. **Unit is carried in the key suffix** (`-w`, `-v`, `-a`, `-kwh`, `-va`, `-c`, `-hz`, `?` for booleans) so a field's dimension is self-describing and never implicit. `Reading` is the only append-only raw fact; every other entity is derived from Readings or from descriptors.

#### `Reading` — "What did the inverter report right now?"
The domain representation of one inverter snapshot — append-only, immutable (ADR-002). One per DATA packet (~60 s). Identity = `device-serial` + `timestamp` (no surrogate id; a device sits at one site, so this pair is unique). `site-id` is carried on the row for filtering and per-site aggregation — it groups the parallel inverters that share a site. This is a **domain entity**: it carries only the solar/energy vocabulary — no byte offsets, no field widths, no decode logic. The hardware mapping (ADR-018) translates a device's protocol into these fields; the Reading itself is device-agnostic.

**Fixed core** (every Reading, every device):

| Field | Type | Unit | Notes |
|---|---|---|---|
| `:reading/timestamp` | inst | — | when the inverter reported |
| `:reading/seq` | int | — | packet sequence |
| `:reading/device-serial` | string | — | which inverter (registry key, ADR-020) |
| `:reading/site-id` | string | — | which site (registry key via ADR-020; groups parallel inverters) |
| `:reading/hardware-id` | keyword | — | which device class produced it (ADR-018) |
| `:reading/received-at` | inst | — | server receive time (latency/debug) |

**Measurement vocabulary** — the canonical field names the entity schema registry validates against (ADR-019). A device's hardware descriptor (ADR-018) selects which of these it produces; a field the descriptor does not declare is absent on that device's readings, and any KPI referencing it reports `:kpi/available? false` (ADR-010).

| Field | Type | Unit | Meaning |
|---|---|---|---|
| `:reading/pv1-voltage-v`, `:reading/pv2-voltage-v` | number | V | per-string PV voltage |
| `:reading/pv1-power-w`, `:reading/pv2-power-w` | number | W | per-string PV power |
| `:reading/pv1-current-a`, `:reading/pv2-current-a` | number | A | per-string PV current |
| `:reading/load-power-w` | number | W | house load (AC output) |
| `:reading/ac-apparent-power-va` | number | VA | AC output apparent power |
| `:reading/grid-power-w` | number | W | grid import (+ = importing; no export per ADR-021) |
| `:reading/battery-voltage-v` | number | V | battery voltage |
| `:reading/battery-power-w` | number | W | battery power, signed (neg = charging, pos = discharging) |
| `:reading/battery-current-a` | number | A | battery current, signed (neg = charging, pos = discharging) |
| `:reading/ac-input-voltage-v` | number | V | AC input (grid-side) voltage |
| `:reading/ac-output-voltage-v` | number | V | AC output voltage |
| `:reading/ac-input-current-a` | number | A | AC input current |
| `:reading/grid-frequency-hz` | number | Hz | grid frequency |
| `:reading/temp-c` | number | °C | inverter temperature |
| `:reading/energy-today-kwh` | number | kWh | energy generated today |
| `:reading/energy-total-kwh` | number | kWh | lifetime energy generated |

**Not stored on `Reading` — derived at compute time (TDD-03):** pv-total power (`pv1+pv2`), self-consumption, self-sufficiency. `Reading` is the raw fact; derivations are KPIs.
**Not stored on `Reading` — encapsulation (ADR-026):** `tenant_id`. The store scopes the tenant; domain code never sees it. (`site_id` *is* on the row — the site, domain-filtered; it is not an isolation key.)
**Not stored on `Reading` — protocol concerns (ADR-018 / protocol doc):** byte offsets, field widths, and decode logic (e.g. how a device derives signed battery power/current from its registers). These belong to the hardware mapping, on the other side of the domain boundary.

#### Pending entity schemas
`Draft`, `Day`, `Overnight`, `Cycle`, `Period` (temporal — derived from Readings), and `Site`, `Incident`, `Tariff`, `BillingCycle`, `Reconciliation` (supporting) — to be defined next, same naming/unit conventions. Each gets its field table, the question it answers (per ADR-002), and its KPI set. All temporal + supporting rows carry `site_id` (the site); `device-serial` is on `Reading` only.

### Tenancy
- **`site_id` is a real local column on every per-site row from day one** (ADR-026): domain-visible and domain-filtered; groups parallel inverters; per-site aggregates (`Day`, `Period`) live here. Cross-site aggregation within a tenant is "don't filter `site_id`" — an ordinary query.
- **`tenant_id` is the structural isolation key, deferred to cloud** (ADR-026): not a local column the domain queries; locally the file boundary (file-per-tenant) or the single-tenant invariant (single-file) isolates. Materialized at export from file identity, transparent to domain code. The discipline footgun that applied to the old `system_id` applies here: domain code must not filter on `tenant_id`, so it is not exposed as a domain-visible column.
- DuckDB schema shaped so the cloud hypertable (time + space partition on `tenant_id`) is a stamp-at-export away — local tables carry `site_id` (and `device-serial` on `readings`) but **no** `tenant_id` column; `tenant_id` is added at export (ADR-026), not present from day one.
- SQLite JSON config tables + `config_history` audit trail (ADR-008); the global/per-tenant split is by **section key** (per-tenant sections scoped by their key, e.g. `tariffs/home`), with **no** local `tenant_id` column — same deferral as time-series.

## Sequences / flows
TODO:
- Reading append (immutable, never mutated) — the one write path from ingestion.
- Day finalisation: Draft replaced on `:day-complete`.
- Period aggregation (merge-periods monoid, ADR-011) and the two-phase `:recompute` for non-derivable fields.
- Cloud migration sequence (ADR-026 shape; ADR-008 mechanism): provision Postgres+TimescaleDB → export (stamp `tenant_id` from the file identity — ADR-026 defers the structural key to this moment; `site_id` is already a column) → bulk `COPY` into space-partitioned hypertables → migrate SQLite→JSONB → swap the clients inside `TenantStore` via `open-store` (record shape + domain namespaces unchanged) → enable row-level isolation on `tenant_id` (Postgres RLS — ADR-008) → validate via reconciliation (ADR-025).

## Invariants & error modes
TODO:
- Reading is append-only; never mutated.
- A local file is a pre-shaped cloud shard — `tenant_id` stamped from the file identity at export (deferred to cloud, ADR-026); never a local column domain code must manage. `site_id` *is* a local column domain code freely filters.
- No data access bypasses `TenantStore` — a leaked direct datasource construction silently breaks the migration path. Domain code never sees `tenant_id`; tenant scoping is encapsulated in the store's clients. Domain code freely filters `site_id` / `device-serial`.
- Row-level-isolation policies (cloud, ADR-008: Postgres RLS) must be correct — a misconfigured policy leaks across tenants; testable per tenant-id.
- BillingCycle accumulation depends on reading completeness — gaps undercount grid import.

## Open / deferred
- File-per-tenant (`data/{tenant-id}.ddb`) vs single-file: deferred behind `open-store`; a single file holds one tenant's sites (via `site_id`) but never multiple tenants (DuckDB has no native row-level isolation — ADR-008), so file-per-tenant is the local multi-tenant path — adopt when a second tenant appears.
- Optional Timescale continuous aggregates for Periods (ADR-011/026) — flagged, not in base migration; would touch the stored-fact determinism model.
- SQLite → per-tenant `config.db` mirroring if full config isolation is later wanted.