# ADR-026: Multi-Tenant Storage Model

## Status
Accepted

## Context
ADR-018 states the system is "designed to scale to multiple tenants, multiple sites, and multiple inverter connections — starting small is a proof-of-concept phase, not a design constraint." ADR-020 introduces `:device/site-id` to group devices into sites. But ADR-008 defines only a single in-process DuckDB file (`data/solar.ddb`) and a single SQLite config — no partitioning, no `tenant_id` anywhere. The design intent (multi-tenant, multi-site) and the persistence model (single-file) do not agree.

The tenancy model has three levels, not two:

- **tenant** — the owner / isolation boundary. One pv-app process may serve one tenant (the start) or several (multi-tenant). Isolation is structural at this level: locally a file-per-tenant boundary, in cloud row-level security on `tenant_id`. **The domain never sees or filters `tenant_id`** — it is the structural key, not a query field.
- **site** — a physical location with one or more inverters. A **real local column on every per-site row** (`site_id`); the domain freely filters it. Parallel inverters at one site share a `site_id`; `device-serial` differentiates them. Aggregates (`Day`, `Period`) are per-site.
- **device** — one inverter, one TCP connection. Differentiated on `Reading` by `device-serial` (ADR-020).

The cloud migration insight drives the decision: in shared-schema cloud, all tenants' data lives in one hypertable space-partitioned by `tenant_id`, regardless of whether the local model was file-per-tenant or single-file. The load-bearing concern is not the local sharding choice but the **client-encapsulated tenant scoping** (`TenantStore`) that survives the migration — domain code stays blind to `tenant_id` across local→multi-tenant→cloud, while freely filtering `site_id` within a tenant.

This is a **model ADR**: it states the storage *capabilities* and *requirements* — per-tenant isolation at the client; a real `site_id` row key for within-tenant partitioning; `device-serial` to separate parallel inverters; global config reachable before any store. The specific technologies — DuckDB isolating by file boundary, Postgres + TimescaleDB isolating by Row-Level Security — are owned by ADR-008. Tech appears here only as the named instantiation of an ADR-008 choice, never as the decision itself. This keeps the model durable across any revision of the storage tech in ADR-008.

## Decision

### Build now — the portable abstraction (correct at 1 tenant, N tenants, and cloud)

Two things, both cheap now and expensive to retrofit (retrofit = rewrite):

1. **All data access goes through a `TenantStore` — a binding record, not an interface.** `TenantStore` is a plain record that carries the bound clients for one tenant. It has no query methods. Domain namespaces are the repositories; they receive a store and use its clients directly, writing queries that filter by `site_id` (and `device-serial`) but never by `tenant_id`.

```clojure
(defrecord TenantStore
  [tenant-id
   time-series   ;; DuckDB datasource — readings, days, periods, incidents (rows carry site_id)
   config])      ;; SQLite datasource — per-tenant tariffs, rules, dashboards

;; Constructed once per session/connection at establishment
;; open-store opens both time-series and config clients; exact config shape defined in TDD-02
(def store (open-store {:tenant-id "home"}))

;; Domain namespaces define their own query vocabulary; queries filter site_id, never tenant_id
(ilanga.domain.readings/latest   store site-id)
(ilanga.domain.readings/in-range store site-id from to)
(ilanga.domain.readings/write!   store reading)   ;; reading carries :reading/site-id, :reading/device-serial

(ilanga.domain.days/by-date   store site-id date)
(ilanga.domain.days/finalize! store day)

(ilanga.domain.incidents/active store site-id)
(ilanga.domain.incidents/close!  store id)

(ilanga.domain.tariffs/active store site-id)
```

`open-store` is the only construction point for a tenant-scoped store. Global config (device registry, hardware descriptors, permission descriptors) is not scoped to a tenant and is accessed separately — not through `TenantStore`. This is a real ordering constraint, not a stylistic split: the device registry (global) is read *without* a store to obtain `tenant-id` and `site-id`, which are then passed to `open-store` (tenant-id) and to query functions (site-id). Global config is the root, reachable before any store exists — which is exactly why it cannot itself be behind a `TenantStore`.

The domain namespace functions shown above are illustrative. The actual query vocabulary for each domain — which functions exist, what arguments they take, what they return — is defined by the ADR and TDD governing that domain (e.g. readings by ADR-018/TDD-01, incidents by ADR-023/TDD-03, tariffs by ADR-021/TDD-06). ADR-026 owns only the `TenantStore` record shape and `open-store`; it does not define domain APIs.

This is the load-bearing abstraction. A session given a `TenantStore` for `"home"` cannot reach another tenant's data — the clients it holds are already bound **and already tenant-scoped**. Domain namespaces never see or forward a `tenant-id`; they receive a store and write queries scoped by `site_id` (e.g. `SELECT … FROM readings WHERE site_id = ?`). The tenant scoping is **encapsulated in the client**, enforced by how `open-store` constructs it — not by domain code. Domain code is therefore structurally unable to cross tenants: it never holds the `tenant-id`, and the client will not return another tenant's rows. **Within a tenant, domain code freely filters `site_id` and `device-serial` — that is normal, supported querying, not a cross-boundary concern.** This is the test of ADR-008's claim that storage is "a contained change behind the persistence layer."

2. **Session→tenant binding via `:permission/tenant-id`** (ADR-013 addendum). A permission descriptor carries the tenant it scopes. Device connections resolve tenant-id and site-id from the device registry (ADR-020); LLM sessions (MCP/chat) resolve tenant-id from their permission-id and scope site-id through their queries. This is the *portable* isolation guarantee — locally it determines which datasource a session may touch; in cloud it sets the row-level-isolation session variable (ADR-008 mechanism). The binding is what survives migration; the mechanism it drives is swapped behind `open-store`.

### `site_id` is a real local column

Unlike the earlier deferred `system_id`, `site_id` is a **real local column on every per-site row from day one.** The design goal is to identify the site at the row: a single DuckDB file (one tenant) holds multiple sites' rows, distinguished by `site_id`, and the domain filters it. This dissolves the old restriction that "a single file holding multiple systems is not a supported mode" — that restriction existed only because there was no column and scoping was per-system at the file boundary. Now the file boundary is the *tenant* boundary, and within a tenant's file, `site_id` partitions sites as an ordinary query column.

Consequences:
- `Reading`, `Draft`, `Day`, `Overnight`, `Cycle`, `Period`, `Incident`, `BillingCycle`, `Reconciliation` all carry `site_id`.
- `device-serial` is on `Reading` (and the live connection) to separate parallel inverters that share a `site_id`.
- Aggregates (`Day`, `Period`) are per-site: a site's `Day` combines that site's inverters. **Cross-site aggregation within a tenant is "don't filter `site_id`" — an ordinary query, supported.**
- `site_id` is domain-visible and domain-filtered. It is *not* an isolation boundary.

### Defer — everything that depends on having >1 tenant, or on a shared-schema backend

The `tenant_id` structural isolation, local sharding by tenant, and the cloud row-level-isolation mechanism are *behind-the-binding*: they change what client `open-store` constructs and what structural key isolates, without touching domain code (which only ever filters `site_id`).

- **`tenant_id` structural isolation → deferred to cloud (shared-schema only).** This is the deferred-to-cloud role: locally the file boundary (file-per-tenant) or the single-tenant invariant (single-file) isolates — `tenant_id` is not a column the domain queries. In a shared schema it is the row-level key. Materialized as the isolation variable at export from file identity, transparent to domain code, which never references it. The discipline-footgun argument that applied to `system_id` applies here instead: domain code must not filter on `tenant_id`, so it is not exposed as a domain-visible column locally.

- **Local sharding (single-file now → file-per-tenant at 2nd tenant).** Which client `open-store` constructs is a *behind-the-binding* choice, but every viable option shares one property: **the client encapsulates tenant scoping** — domain code never filters and never sees `tenant_id`, though it freely filters `site_id`.
  - **Single file, single tenant (the start state)** — one file, `tenant_id` constant `"home"`, but potentially *multiple sites' rows* distinguished by `site_id`. Tenant encapsulation is trivial: with one tenant, every row belongs to `home`, so an unscoped-by-tenant query is already tenant-scoped. Cross-site queries within the file are supported via `site_id` (the column exists). Valid for a single tenant; isolation is the single-tenant invariant.
  - **File-per-tenant `data/{tenant-id}.ddb` (local multi-tenant)** — `open-store` opens the file for the given tenant-id. The **file boundary is the tenant encapsulation**: storage-enforced and unbypassable, a session's client physically cannot reach another tenant's rows. Each tenant's file holds that tenant's sites, distinguished by `site_id`. Per-tenant backup/restore (copy a file); no cross-tenant write contention. Trade: a connection pool keyed by tenant-id.
  - **Shared schema + row-level isolation (cloud — ADR-008 trigger)** — one shared schema, but the connection's session variable plus row-level policies on `tenant_id` **encapsulate tenant scoping in the client** — storage-enforced, the same unbypassable property as file-per-tenant, expressed at the DB instead of the filesystem (ADR-008: Postgres Row-Level Security). `site_id` remains an ordinary column the domain filters.

  Start single-file-single-tenant (with `site_id` already present). When a second tenant appears, move to file-per-tenant inside `open-store` (no caller changes — domain code already filters `site_id`, never `tenant_id`) — required, not optional, to keep tenant encapsulation storage-enforced. Cloud re-expresses the same encapsulation at the DB. The *moment* of local sharding is contained behind the binding, so it is not a blocking decision and need not be resolved before building.

### Config store — always a global layer

The **device registry** (serial → tenant-id + site-id + hardware-id) is inherently global — it is the routing table that knows all devices. So config is always global + per-tenant, regardless of the time-series sharding choice:

- **Global config:** device registry, permission descriptors (each carrying `:permission/tenant-id`), prompt templates, hardware descriptors (code-side). No `tenant_id`. Reachable before any store exists.
- **Per-tenant config:** tariffs, active rules, dashboards, site config, load-shedding area. Scoped to one tenant; within it, per-site where site-specific.

This ADR requires only the global/per-tenant split and that global config precede any store. The concrete config store and whether it is one file or per-tenant files is ADR-008's choice (currently a single SQLite with per-tenant sections; mirror to per-tenant `config.db` if full isolation is later wanted — behind the same `open-store` binding).

### Migration to cloud (ADR-008 trigger)

The migration swaps the clients inside `TenantStore` and adds the row-level key. The mechanism detail — hypertable time + space partitioning on `tenant_id`, bulk loading, compression policies, row-level-isolation policy syntax, SQLite→JSONB — is owned by ADR-008; this ADR states only the portable shape:

1. Provision a shared-schema time-series backend with time + space partitioning on `tenant_id` (ADR-008: Postgres + TimescaleDB). `site_id` and `device-serial` travel as ordinary columns. Preserve the global/per-tenant config split and audit trail.
2. Export each per-tenant file — `tenant_id` is stamped from file identity at export (the structural isolation key, materialized now). `site_id` is already a column (it always was). No domain code references `tenant_id`.
3. Swap the datasource returned by `open-store` (build-now #1): local client → cloud pool. The `TenantStore` record shape and all domain namespaces are unchanged; only the clients it carries change.
4. Enable row-level isolation on `tenant_id` (ADR-008: Postgres RLS). The session→tenant binding (build-now #2) sets the isolation variable.
5. Validate with reconciliation (ADR-025): same deterministic engine, same data → same KPIs; cross-check per-site totals within a tenant.

What is portable (unchanged): the engine, KPI computation, entity model, descriptors, the deterministic core, device-registry logic, the LLM/analysis layer, the session→tenant binding, all domain namespaces (which filter `site_id`, never `tenant_id`). Everything above the `TenantStore` clients.
What is swapped: the datasources inside `TenantStore`, the connection model (file pool → cloud pool), the isolation mechanism (file separation → row-level isolation) — all ADR-008-owned mechanisms re-expressing the same encapsulation.

### Optional cloud-native opportunity (not required)

ADR-011's stored day/month/year Periods + the two-phase `:recompute` could be backed by a continuous-aggregate layer in cloud (ADR-008: Timescale continuous aggregates). Flagged, not folded into the base migration — it shifts Periods from stored facts to maintained views, touching the determinism/auditability model (ADR-010/017 treat Period values as stored, carry-able data). Keep stored Periods for auditability; treat continuous aggregates as a read-side acceleration only.

## Rationale
- The three-level model (tenant → site → device) matches reality: an owner has sites, a site has inverters. Isolation belongs at the owner (tenant) level; identification-at-the-row belongs at the site level; inverter differentiation belongs at the device level. Conflating tenant and site (the earlier 1-tenant-1-installation assumption) forced the column to be deferred and made cross-installation aggregation a special case.
- Making `site_id` a real local column dissolves the "single file cannot hold multiple systems" restriction: the file is the tenant boundary, and within it `site_id` is an ordinary query column. Cross-site-within-tenant aggregation becomes "don't filter `site_id`" — a normal query, not a cross-boundary problem.
- `tenant_id` takes the deferred/structural role `system_id` had: it is the cloud row-level key, locally the file boundary, and never a domain query field. The discipline footgun (domain must not filter it) is avoided by not exposing it as a domain-visible column.
- The cloud migration insight is decisive: file-per-tenant and single-file converge to the same shared-schema hypertable in cloud, so the local sharding choice is not cloud-relevant. What matters across local→multi-tenant→cloud is the client-encapsulated tenant scoping (`TenantStore`), which is cheap now and a rewrite later.
- Decoupling the portable abstraction (build now) from the tenant sharding + cloud mechanism (defer) means the multi-tenant-vs-multi-site question stops blocking the build — the abstraction is the same either way; only the deferred details depend on having >1 tenant or a shared schema, and they are contained inside `open-store` or behind export.
- Stating capabilities, not technologies, keeps this model ADR durable across revisions of the storage tech owned by ADR-008 — the same descriptor/function and declaration/assembly separation discipline as ADR-005/019.

## Consequences
- (+) The system is correct at 1 tenant, N tenants, and cloud with no rewrites between — the abstraction carries.
- (+) Multi-tenant and multi-site no longer block the build; tenant sharding is deferred behind `open-store` / export, and `site_id` is present from day one so multi-site works in the single-tenant start file.
- (+) Parallel inverters are first-class: `device-serial` on `Reading` differentiates them; per-site aggregates combine them; no special handling.
- (+) Cross-site aggregation within a tenant is an ordinary query (don't filter `site_id`); cross-tenant is a structural boundary — clean separation of the two.
- (+) Local isolation is structural (file separation when sharded) and portable (session binding + row-level isolation in cloud).
- (+) Migration is export-stamp + backend swap + enable row-level isolation, not a rewrite.
- (+) The model is tech-agnostic: stating capabilities means it survives ADR-008 revising the storage tech without going stale.
- (-) Every data access must go through the store — discipline cost; constructing a datasource directly anywhere silently breaks the migration path. (Mitigated by making `open-store` the only construction point and the store the only data-access surface.)
- (-) The session→tenant binding adds a field to permission descriptors and a resolution step at session establishment.
- (-) Cloud row-level-isolation policies must be written and tested correctly — a misconfigured policy leaks across tenants. One-time setup, testable per tenant-id. The mechanism is owned by ADR-008; the binding that drives it is owned here.
- (-) File-per-tenant, when adopted, adds a connection pool keyed by tenant-id and N open files — fine for 1–few tenants, awkward for many (at which point cloud is the answer anyway).

## Open / deferred
- **`open-store` config shape and construction** — the exact arguments, client initialisation, and connection pooling strategy are defined in TDD-02 (Persistence, entity model & tenancy).
- **Domain namespace vocabularies** — the query and write functions for each domain (`ilanga.domain.readings`, `ilanga.domain.days`, `ilanga.domain.incidents`, etc.) are defined by the ADR and TDD governing that domain; not by this ADR.
- **`tenant_id` structural isolation** — deferred to cloud (shared-schema only) as the row-level key; materialized at export from the file identity, transparent to domain code. Locally the file boundary (file-per-tenant) or the single-tenant invariant (single-file) isolates — no column needed.
- **File-per-tenant sharding** — deferred until a second tenant arrives; contained inside `open-store`, no caller changes required.
- **Cloud row-level-isolation mechanism detail** (policy syntax, hypertable space-partitioning, bulk loading, compression) — owned by ADR-008's cloud-migration addendum.