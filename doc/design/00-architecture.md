# Ilanga — Architecture (00)

**Status:** Draft — in progress.

## Project name
**Ilanga** — from the Nguni (isiZulu/isiXhosa) word for *sun* / *day*. A residential solar monitoring system; the name names the thing the system is built around. Pronounced *ee-LAH-ngah*.

## Purpose & scope
A residential solar monitoring system. It receives live inverter telemetry over TCP, stores it as immutable time-series facts, computes energy KPIs and billing metrics deterministically, and delivers interpreted analysis through an LLM interface.

The architecture has one spine: a loop in which an LLM authors declarative programs (rules, KPIs, templates) in registration mode, a deterministic engine runs those programs against measured data, and the results return to the LLM as pre-assessed context packages for interpretation in analysis mode. The LLM is never in the execution path; it authors and interprets only.

This document describes how the subsystems compose — the interfaces between them, the data that crosses each seam, and the invariants that hold everywhere. It does not repeat per-subsystem detail; each subsystem TDD (01–06) expands its own box, and TDD-07 covers the runtime platform they share. It does not re-litigate architectural decisions; the governing ADRs own those.

**Out of scope:** inverter-specific protocol details (see `doc/protocol/`), per-subsystem schemas and flows (see TDDs 01–06), the runtime platform (see TDD-07), and individual architectural decisions (see ADRs).

## Governing ADRs
- ADR-001 Clojure as implementation language — Accepted
- ADR-004 Two-layer architecture (data + meta) — Accepted
- ADR-014 LLM as registrar and analyst (the loop pattern) — Accepted

## Requirements

1. **Receive live inverter telemetry over TCP**
   Inverters initiate outbound TCP connections and push binary packets at a regular interval. The system must accept and maintain these connections continuously.

2. **Decode device-specific binary packets into canonical readings**
   Each inverter model has its own packet format. The system must translate device-specific encodings into a protocol-agnostic reading without coupling the rest of the system to any one device format.

3. **Store readings as immutable, append-only time-series facts**
   Readings are measurements of physical reality — they cannot be corrected, only supplemented. Immutability enables auditability and deterministic recomputation.

4. **Compute energy KPIs and billing metrics deterministically**
   Given the same facts and the same descriptors, the system must always produce the same output. Results must never depend on when or how many times the computation runs.

5. **Detect, track, and close solar events (incidents)**
   The system must evaluate authored rules against live data, open incidents when conditions are met, and close them when they are no longer active.

6. **Deliver pre-assessed results to an LLM for interpretation**
   The system must package computed KPIs and incidents for LLM consumption. Assessment is pre-computed; the LLM interprets, not computes.

7. **Allow an LLM to author descriptors at runtime without a deploy**
   Rules, KPIs, and templates must be expressible as data that an LLM can write into the config store without a code change or redeploy.

8. **Support multiple inverter models**
   Hardware mappings are configurable — a new inverter model requires a new descriptor, not new parsing code.

9. **Support multiple tenants, sites, and devices**
   The system must isolate data by tenant and identify the site at the row from the start, even when running a single tenant/site today. Parallel inverters at one site are first-class.

10. **Operate self-contained**
    The default configuration must have no external service dependencies — data sovereignty and uptime independence are explicit goals.

## Constraints

1. **Clojure on the JVM (ADR-001)**
   The implementation language is Clojure. Data structures over objects; immutable by default; REPL-driven development. JVM ecosystem is available (Netty, JDBC, etc.).

2. **Two-layer separation — data and meta (ADR-004)**
   Immutable facts (data layer) and configuration (meta layer) are strictly separated. The meta layer is split: registry is code and changes only on deploy; config store is data and changes at runtime. These two layers must never be conflated.

3. **LLM is never in the execution path (ADR-014)**
   The LLM authors descriptors (registration mode) and interprets pre-assessed results (analysis mode). It never executes, never computes KPIs, and never runs rules. The engine is always the executor.

4. **No `eval` — one whitelisted evaluator (ADR-015)**
   The only expression evaluator is `eval-expr`. There is no general `eval`. All LLM-authored programs are data interpreted by `eval-expr`, not executable code.

5. **All data access through `TenantStore` — domain code filters `site_id`, never `tenant_id` (ADR-026)**
   Domain namespaces receive a `TenantStore` and write queries that filter by `site_id` (and `device-serial`) — never by `tenant_id`. Tenant scoping is encapsulated in the client, not in domain code. `open-store` is the only construction point. Global config (device registry, hardware descriptors) is structurally prior — it is read before a store exists to resolve the tenant-id and site-id, and is never behind a `TenantStore`.

6. **`tenant_id` isolation is deferred to cloud; `site_id` is a real local column (ADR-026)**
   Local tables carry `site_id` (the site — domain-filtered, groups parallel inverters) but **not** `tenant_id` — the file boundary (file-per-tenant) or single-tenant invariant (single-file) isolates tenants, so `tenant_id` is a discipline footgun if exposed and is deferred. At cloud migration, `tenant_id` is stamped from the file identity at export — transparent to domain code, which filters `site_id` and never references `tenant_id`.

7. **One permission system across all interfaces (ADR-013)**
   MCP, internal chat, HTTP ingest, and device connections all resolve to the same permission-id. There is no interface-specific access control.

8. **KPI availability is always explicit (ADR-010)**
   A KPI is either present with a value, or present with `:kpi/available? false` and a reason. Silent nil is never acceptable.

## Components
| # | Subsystem | TDD | Governing ADRs |
|---|---|---|---|
| 01 | Ingestion & device identity | [01-ingestion-device-identity.md](01-ingestion-device-identity.md) | 018, 020 |
| 02 | Persistence, entity model & tenancy | [02-persistence-entity-tenancy.md](02-persistence-entity-tenancy.md) | 002, 008, 026 |
| 03 | Engine: pipeline, KPIs & scope | [03-engine-pipeline-kpi-scope.md](03-engine-pipeline-kpi-scope.md) | 003, 007, 009, 010, 011, 019 |
| 04 | LLM surface: authoring, validation, interpretation | [04-llm-surface.md](04-llm-surface.md) | 012, 014, 015, 016, 017 |
| 05 | Registry, config & permissions | [05-registry-config-permissions.md](05-registry-config-permissions.md) | 004, 005, 006, 013 |
| 06 | Tariff, billing & external integrations | [06-tariff-billing-integrations.md](06-tariff-billing-integrations.md) | 021, 022, 023, 024, 025 |
| 07 | Runtime platform (lifecycle, concurrency, observability) | [07-runtime-platform.md](07-runtime-platform.md) | 007, 008, 018, 027, 028, 029 |

## Interfaces

The seams between subsystems — what crosses each boundary and which TDDs own each side. Function signatures and schemas are in the subsystem TDDs; this table names the contracts.

| Seam | From | To | What crosses it | Detail in |
|---|---|---|---|---|
| TCP connection | Inverter (external) | 01 Ingestion | Binary packets over persistent TCP | TDD-01 |
| Device registry lookup | 05 Registry/config | 01 Ingestion | Serial → tenant-id + site-id + hardware-id + permission-id (global config, no store) | TDD-01, TDD-05 |
| Reading write | 01 Ingestion | 02 Persistence | Canonical Reading via `solar.readings/write!` (TenantStore) | TDD-01, TDD-02 |
| Ingestion handoff | 01 Ingestion | 03 Engine | Canonical Reading on core.async channel | TDD-01, TDD-03 |
| Signal dispatch | 03 Engine (internal) | 03 Engine (internal) | Named signals (`:new-reading`, `:day-complete`, `:tick`, …) driving pipeline steps | TDD-03 |
| Entity reads | 03 Engine | 02 Persistence | Domain namespace reads via TenantStore | TDD-02, TDD-03 |
| Descriptor reads | 03 Engine | 05 Registry/config | Rules, KPIs, templates from registry + config store | TDD-03, TDD-05 |
| Context package | 03 Engine | 04 LLM surface | Pre-assessed KPI map + incidents (Malli-validated map) | TDD-03, TDD-04 |
| Descriptor writes | 04 LLM surface | 05 Registry/config | Validated descriptors authored by LLM in registration mode | TDD-04, TDD-05 |
| MCP / chat | External LLM client | 04 LLM surface | Tool calls (registration) and analysis requests | TDD-04 |
| HTTP ingest | 06 Integrations | 03 Engine | Automation events (load shedding, tariff updates) via action registry | TDD-03, TDD-06 |

**Gap:** user-facing output — where LLM analysis results (reports, natural language, actions) are delivered is not covered by any subsystem TDD. Carried in Open / deferred.

## Data structures

The system operates on eleven canonical entities. Each is defined fully in TDD-02; named here for orientation.

| Entity | What it represents |
|---|---|
| `Reading` | A single decoded inverter snapshot — immutable, append-only |
| `Draft` | Accumulating intra-day state before the day is finalised |
| `Day` | A finalised solar day — the primary unit of analysis |
| `Overnight` | The battery/grid period between two solar days |
| `Cycle` | One Day + one Overnight — the repeating unit |
| `Period` | A combinable aggregate (week, month, year) — merged KPIs only, no constituent instances |
| `Site` | Site-level metadata and configuration |
| `Incident` | A solar event opened by a rule, closed when condition clears |
| `Tariff` | A configured electricity pricing model with a deterministic calculator |
| `BillingCycle` | A municipal billing period accumulating energy and cost |
| `Reconciliation` | A comparison of computed cost against an actual municipal bill |

Two storage backends, defined in TDD-02: **DuckDB** for time-series facts (Reading, Draft, Day, Overnight, Cycle, Period, Incident, BillingCycle, Reconciliation), **SQLite** for configuration (the Tariff and Site entities, plus the rule/KPI/template descriptors held as config data). Note the distinction: `Incident` *events* are time-series facts in DuckDB; the `Incident` *rules* that open them are config descriptors in SQLite. Both accessed exclusively through `TenantStore`.

## Flows & sequences

### The loop

One complete turn of the architecture spine, from raw inverter packet to authored descriptor.

**1. Ingestion (01)**
An inverter opens a TCP connection. Aleph creates a Manifold stream for it. On connect, the serial number is extracted from the announce packet and looked up in the global device registry — resolving hardware-id, tenant-id, site-id, and permission-id. These are bound to the stream for its lifetime. `open-store` constructs a `TenantStore` for the resolved tenant-id; `site-id` is stamped onto each Reading this connection produces.

Each subsequent packet is accumulated into a frame, CRC-validated, and XOR-decrypted. The generic decoder reads field offsets and types from the hardware descriptor and extracts values. The result is Malli-validated as a canonical `Reading`. The Reading is written via `solar.readings/write!` (TenantStore) and placed on the core.async ingestion channel.

> **Determinism boundary enters here.** The Reading is now an immutable fact. Everything from this point to the context package is deterministic — same descriptors + same data → same output, always.

**2. Pipeline (03)**
The pipeline consumer picks up the Reading from the channel and emits a `:new-reading` signal. The pipeline rule for `:new-reading` runs `:draft/update` — the in-progress Draft for the current solar day accumulates the new values.

When the solar day boundary is crossed (ADR-003), `:day-complete` is emitted. This triggers an ordered chain:

```
:day/finalize         → Draft sealed into an immutable Day
:overnight/compute    → Overnight computed from last Day's end to this Day's start
:cycle/compute        → Cycle assembled (Day + Overnight)
:rules/evaluate       → authored rules evaluated; Incidents opened or closed
:billing-cycle/update → BillingCycle updated with Day's energy and cost
```

KPIs are computed at each stage with explicit availability (ADR-010) — a KPI is present with a value or present with `:kpi/available? false` and a reason. Never silently nil.

**3. Context package (03 → 04)**
The engine assembles a context package: the full KPI map with `:kpi/assessment` pre-computed, active Incidents, `:season`, `:package/version`, and `:package/as-of`. The package is validated against the Malli `::context-package` schema. Failure degrades gracefully to "analysis unavailable" — no partial data is sent.

> **Determinism boundary exits here.** The context package is a deterministic product of facts and descriptors.

**4. Analysis mode (04)**
The context package is delivered to the LLM surface (MCP or internal chat). The LLM reads pre-computed `:kpi/assessment` values and generates natural language interpretation — a report, an alert, a recommendation. The LLM interprets; it does not compute. Output is delivered to the user.

**5. Registration mode (04 → 05, optional)**
In a separate turn, the LLM may propose a new descriptor — a rule, a KPI definition, a template. The descriptor is structurally validated (Malli), scope-validated (`validate-scope` against `::scope-context`), and field-existence checked against the entity schema. If valid, it is written to the config store with an audit entry. On the next engine run, the new descriptor is live — the loop closes.

---

### Data lifecycle of one reading

The journey of a single 60-second inverter snapshot from raw bytes to a monthly reconciliation line item.

1. **Raw bytes** — a field at payload offset 174 carries the day's energy generation (width under verification — a single byte caps at 25.5 kWh, which a high-yield summer day on this array may exceed; see ADR-018 / the protocol note). The generic decoder reads it: `(/ (bit-and (aget payload 174) 0xFF) 10.0)` → e.g. `18.3` kWh.

2. **Reading** — the decoded value becomes `:reading/energy-today-kwh 18.3` in a canonical Reading map. Written to DuckDB via `solar.readings/write!`. **Immutable from this point** — the raw fact is never mutated.

3. **Draft** — the `:new-reading` signal updates the current Draft. `energy-today` in the Draft reflects the latest reading's value.

4. **Day** — at the solar day boundary, the Draft is sealed into a `Day`. `:day/energy-today-kwh` is carried from the final reading of the day.

5. **Period** — Days are merged into Periods (week, month, year) via `merge-periods`. The monthly Period accumulates `:period/energy-total-kwh` across all days. Periods store merged KPIs only — no constituent Day instances.

6. **BillingCycle** — the monthly Period's energy totals feed the BillingCycle. The tariff calculator applies the configured rate model to compute cost.

7. **Reconciliation** — when the municipal bill arrives (via Mobele automation, ADR-025), the engine computes a `Reconciliation` comparing the BillingCycle's computed cost against the actual billed amount. The delta is a KPI. The same reading that started as a byte at offset 174 is now a line in a monthly cost comparison.

## Project structure
TODO: namespace layout, directory conventions, entry point, where each subsystem lives in the codebase.

## Runtime platform

The cross-cutting runtime — component lifecycle, concurrency/thread model, and observability — is not part of any domain subsystem. It is covered in **TDD-07 (Runtime platform)**, governed by ADR-027 (lifecycle), ADR-028 (concurrency), and ADR-029 (observability), all pending. The one architectural fact that is settled here: the lifecycle is two-layered — app components (config-store and DuckDB datasources, TCP server, engine/pipeline, `:tick` timer) start once in dependency order; per-session `TenantStore` bindings are constructed on demand over those datasources (ADR-026/020), not as boot-time singletons.

## Invariants
TODO:
- Determinism: same descriptors + same data → same KPIs, always.
- Explicit availability (ADR-010): KPIs are never silently nil.
- Default-deny scope (ADR-019): a descriptor references only what its declared context provides.
- One permission system (ADR-013): every interface — MCP, chat, ingest, device — resolves to a permission-id bound to a system.
- Tenant scoping is encapsulated in the `TenantStore` client (ADR-026) — domain code never sees or filters on `tenant_id`; it freely filters `site_id` (a real local column). `tenant_id` exists only in cloud, stamped at export.

## Error modes
TODO

## Open / deferred
TODO: carry-forward decisions not yet built — file-per-system sharding (ADR-026), cloud migration (ADR-008), `:tick` interval + consumers (ADR-007), energy-today field-width verification (ADR-018 / protocol doc), backpressure policy on ingestion→engine channel, human/LLM session auth UX, component lifecycle (ADR-027), concurrency model (ADR-028), observability (ADR-029).
