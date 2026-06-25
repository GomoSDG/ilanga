# 02 — Persistence, Entity Model & Tenancy

**Status:** In progress — persistence core (`TenantStore`, `readings` DDL, `ilanga.domain.readings/*` vocabulary, tenancy) flushed; remaining entity schemas + their DDL (Day, Period, Cycle, Incident, Tariff, BillingCycle, Reconciliation) come with TDD-03.

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

**The config client is a scoped wrapper.** SQLite holds all tenants' config in one file, so `open-store` returns a thin wrapper (not the raw datasource) that knows `tenant-id` and prefixes section keys. Domain config namespaces call it with a *logical* section name (`ilanga.domain.tariffs/active` reads `:tariffs`); the wrapper maps `:tariffs` → `"tariffs/home"`. Domain code never sees or types `tenant-id`. *(Proposed mechanism — ADR-026 mandates "client encapsulates scoping"; the wrapper is how TDD-02 satisfies it for SQLite. Redirectable.)*

**`tenant-id` is on the record for internal use only** — the config wrapper, the cloud session var, and export stamping read it; domain code does not. The structural tenant scoping means domain code has no *need* to filter on it (it filters `site_id` instead); the config wrapper means it has no *need* to scope with it. Not reading it is a discipline, backed by structure where structure is available.

**Global config is not behind a `TenantStore`.** Device registry, permission descriptors, prompt templates, and hardware descriptors are global — read without a store, before `open-store` is ever called (ADR-026). Global config access is a separate surface owned by TDD-05.

**Datasources** — both via `next.jdbc` (same library, different JDBC drivers, ADR-008): DuckDB `{:dbtype "duckdb" :dbname "data/solar.ddb"}`, SQLite `{:dbtype "sqlite" :dbname "data/config.db"}`.

### Domain query vocabulary

The `ilanga.domain.readings/*` read/write functions — the read API the dashboard (TDD-08) and the engine/KPI layer (TDD-03) call. Queries take `site-id` explicitly; `tenant_id` is never an argument. `device-serial` is on the row and filterable but is not a required read argument (a site may hold parallel inverters).

```clojure
(ilanga.domain.readings/latest   store site-id)        ;; => the most-recent Reading for the site
(ilanga.domain.readings/in-range store site-id from to) ;; => [Reading], ts-ascending, ts ∈ [from, to)
(ilanga.domain.readings/write!    store reading)        ;; => idempotent append (see Sequences / flows)
```

- `latest` — the highest-`ts` Reading for the site. Single-device now; for parallel inverters this returns the most-recent across devices (per-device latest is a future `latest-by-device`, not needed yet).
- `in-range` — Readings for the site with `ts ∈ [from, to)`, ordered ascending. The historical-view and KPI read path (TDD-03).
- `write!` — the one append path (from ingestion, ADR-018). Appends the Reading; on identity conflict (a row with the same `(device_serial, ts)` already exists) the incoming Reading is **dead-lettered as `identity-conflict`, not silently dropped** — see Sequences / flows. An exact field-identical replay is a no-op (idempotent ingest); only a *differing* same-identity reading is dead-lettered.

No `update!`, no `delete!` — immutability is enforced by the absence of those functions in the vocabulary (correction is supplementation, ADR-002).

Per-domain vocabularies for the other entities (`ilanga.domain.days`, `…periods`, `…incidents`, `…tariffs`, `…billing-cycle`, `…reconciliation`) are defined alongside TDD-03, with their table DDL.

### `readings` DDL

```sql
CREATE TABLE readings (
  ts             TIMESTAMPTZ NOT NULL,        -- when the inverter reported; stored UTC
  seq            INTEGER     NOT NULL,         -- packet sequence (stored, not part of identity)
  device_serial  VARCHAR     NOT NULL,         -- which inverter (registry key, ADR-020)
  site_id        VARCHAR     NOT NULL,         -- which site (real local column, ADR-026)
  hardware_id    VARCHAR     NOT NULL,         -- which device class (ADR-018)
  received_at    TIMESTAMPTZ NOT NULL,         -- server receive time
  -- measurement vocabulary (canonical field names, ADR-019); absent on a device = NULL
  pv1_voltage_v        DOUBLE,  pv2_voltage_v        DOUBLE,
  pv1_power_w          DOUBLE,  pv2_power_w          DOUBLE,
  pv1_current_a        DOUBLE,  pv2_current_a        DOUBLE,
  load_power_w         DOUBLE,
  ac_apparent_power_va DOUBLE,
  grid_power_w         DOUBLE,
  battery_voltage_v    DOUBLE,  battery_power_w      DOUBLE,  battery_current_a  DOUBLE,
  ac_input_voltage_v   DOUBLE,  ac_output_voltage_v  DOUBLE,
  ac_input_current_a   DOUBLE,
  grid_frequency_hz    DOUBLE,
  temp_c               DOUBLE,
  energy_today_kwh     DOUBLE,  energy_total_kwh     DOUBLE,
  PRIMARY KEY (device_serial, ts)            -- identity (ADR-002); conflict target for idempotent write!
);
```

- `TIMESTAMPTZ`, stored UTC; reported in local time (UTC+2) at the output boundary (TDD-08), never stored local.
- No `tenant_id` column (ADR-026): locally the file boundary isolates; `tenant_id` is stamped at export. `site_id` is the domain-filtered column.
- Identity = `(device_serial, ts)` (ADR-002) → `PRIMARY KEY`, and the `ON CONFLICT` target for idempotent `write!`. `seq` is stored but deliberately **not** part of identity — see the noted assumption below.
- Measurement columns are nullable: a field a device's hardware descriptor does not produce is `NULL`, surfaced as `:kpi/available? false` (ADR-010). Adding a canonical measurement = entity-schema change + DDL migration (rare; protocol changes already require a deploy, ADR-018).
- Index: DuckDB zone-maps over append-sorted `ts` suffice for `in-range` at single-home volume; an explicit index on `(site_id, device_serial, ts)` is noted but deferred until volume warrants it.

**Identity assumption — noted (open decision, collected in [ADR-032](../adr/ADR-032-reading-identity.md)).** `(device_serial, ts)` assumes no two *distinct* readings from one device share a timestamp. Growatt timestamps can be coarse (back-to-back packets may share a `ts`, differing in `seq`), so under `ON CONFLICT (device_serial, ts) DO NOTHING` a genuinely-distinct same-`ts` reading is silently dropped — *treated as a replay*, a loss of the second snapshot. `seq` is intentionally excluded because it is a Growatt/protocol artifact not guaranteed across inverters. This is an unresolved conflict with real failure modes on both sides; the full options, tradeoffs, analysis-needed, and triggers to decide are collected in ADR-032 (Draft). Until then the assumption holds for the single Growatt site; resolving it is a deliberate, versioned change (new/flipped ADR), not a silent edit.

### `dead_letter_readings` DDL

Readings that must not enter the `readings` fact table are quarantined here — **never silently dropped** (TDD-00 error modes, principle 1). Two paths feed it:
- **Ingest failures** — Malli validation failure, decode failure, CRC failure, unknown serial — caught upstream of `write!`, with raw bytes available (`payload`).
- **Identity conflict** — a `write!` whose `(device_serial, ts)` already exists *and differs* from the kept row. The incoming (decoded, validated) Reading is dead-lettered; the existing row stays (immutability — never overwritten). This **collects the evidence the identity decision needs** (ADR-032): query `readings` joined to `dead_letter_readings` on `(device_serial, ts)` where `reason = 'identity-conflict'` to see both the kept and the challenging reading.

```sql
CREATE TABLE dead_letter_readings (
  received_at    TIMESTAMPTZ NOT NULL,         -- server receive time (always known)
  ts             TIMESTAMPTZ,                  -- best-effort: packet ts if parseable, else NULL
  device_serial  VARCHAR,                      -- best-effort; NULL if unparseable
  site_id        VARCHAR,                       -- best-effort from registry; NULL if unresolved
  hardware_id    VARCHAR,
  reason         VARCHAR     NOT NULL,          -- malli | decode | crc | unknown-serial | identity-conflict
  payload        BLOB,                         -- raw bytes (decode/crc/unknown-serial); NULL for identity-conflict
  data           JSON                          -- decoded Reading, when available (identity-conflict; malli partial); NULL when only raw bytes exist
);
```

Read-only for analysis/replay; never feeds KPIs. Its write is a plain `INSERT` (no idempotency target — a repeated invalid packet may be recorded twice; acceptable, it is diagnostic, not a fact).

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
- **Reading append (the one write path)**: ingestion decodes a DATA packet → Malli-validates the Reading → `ilanga.domain.readings/write! store reading`. `write!` attempts the `INSERT`; on identity conflict it does **not** silently no-op — it compares the incoming Reading to the existing row: an exact field-identical replay is a no-op (idempotent ingest); a *differing* same-`(device_serial, ts)` reading is written to `dead_letter_readings` as `identity-conflict` (the existing row stays, immutable). Append-only; never UPDATE/DELETE. The writer emits nothing downstream; the pipeline's `:new-reading` signal is emitted off the core.async channel (ADR-018/007), not by `write!`.
- **Day finalisation** (TDD-03): Draft replaced on `:day-complete` — `ilanga.domain.days/finalize! store day` upserts the finalised per-site Day. DDL pending TDD-03.
- **Period aggregation** (TDD-03): merge-periods monoid (ADR-011) + two-phase `:recompute` for non-derivable fields — upserts per-site Period rows. DDL pending TDD-03.
- **Cloud migration sequence** (ADR-026 shape; ADR-008 mechanism): provision Postgres+TimescaleDB → export (stamp `tenant_id` from the file identity — ADR-026 defers the structural key to this moment; `site_id` is already a column) → bulk `COPY` into space-partitioned hypertables → migrate SQLite→JSONB → swap the clients inside `TenantStore` via `open-store` (record shape + domain namespaces unchanged) → enable row-level isolation on `tenant_id` (Postgres RLS — ADR-008) → validate via reconciliation (ADR-025).

## Invariants & error modes
- **Append-only**: no `update!`/`delete!` in the vocabulary; a Reading is never mutated (ADR-002). Correction is supplementation.
- **Idempotent `write!`, conflicts collected not dropped**: an exact-replay (field-identical to the existing row) is a no-op — no duplicate, no error, no dead-letter. A *differing* same-`(device_serial, ts)` reading is **dead-lettered as `identity-conflict`**, the existing row kept — collected for analysis (ADR-032), never silently dropped. Idempotent ingest holds: no duplicate fact, no error.
- **Readings are quarantined, not silently dropped**: a Reading that fails Malli validation (or whose packet fails decode/CRC, or whose serial is unknown) goes to `dead_letter_readings` — never to `readings`. So does a *differing* same-identity reading at `write!` time (`identity-conflict`). The fact table holds only validated, identity-unique facts; nothing is silently lost (TDD-00 error modes, principle 1).
- **No data access bypasses `TenantStore`**: a leaked direct datasource construction silently breaks the migration path. Domain code never sees `tenant_id`; tenant scoping is encapsulated in the store's clients. Domain code freely filters `site_id` / `device-serial`.
- **`tenant_id` never a local column**: a local file is a pre-shaped cloud shard — `tenant_id` stamped from file identity at export (deferred to cloud, ADR-026); never a column domain code manages. `site_id` *is* a local column domain code freely filters.
- **Cloud RLS policies** (ADR-008: Postgres RLS) must be correct — a misconfigured policy leaks across tenants; testable per tenant-id. (Mechanism owned by ADR-008; the binding that drives it by ADR-026.)
- **BillingCycle accumulation** depends on reading completeness — gaps undercount grid import.

## Open / deferred
- File-per-tenant (`data/{tenant-id}.ddb`) vs single-file: deferred behind `open-store`; a single file holds one tenant's sites (via `site_id`) but never multiple tenants (DuckDB has no native row-level isolation — ADR-008), so file-per-tenant is the local multi-tenant path — adopt when a second tenant appears.
- Optional Timescale continuous aggregates for Periods (ADR-011/026) — flagged, not in base migration; would touch the stored-fact determinism model.
- SQLite → per-tenant `config.db` mirroring if full config isolation is later wanted.