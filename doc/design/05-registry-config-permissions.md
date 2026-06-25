# 05 — Registry, Config & Permissions

**Status:** Stub — skeleton only; implementation detail pending.

## Purpose & scope
The substrate: the two-layer architecture (data layer = immutable facts; meta layer = registry at code/deploy-time, config store at data/runtime), the descriptor+function registry pattern, actions as function+descriptor, and the single permission system that gates every interface. This is what the LLM authors *into* (04) and what the engine reads *from* (03). **Excludes** the DSL grammars (04) and entity storage (02).

## Governing ADRs
- ADR-004 Two-layer architecture (data + meta) — Accepted
- ADR-005 Registry vs config store — Accepted
- ADR-006 Actions as function and descriptor — Accepted
- ADR-013 Shared permission system — Accepted

## Interfaces
TODO:
- Registry (deploy-time, code): function entries keyed by dispatch keyword (`:charge/type`, `:action/id`, `:compute` step, charge calculators, scope extractors).
- Config store (runtime, data): descriptors as data — tariffs, rules, dashboards, permissions, device registry, prompt templates.
- Action catalog: the surface the LLM is shown (filtered by permission before send — the LLM never sees tools it can't call).
- Permission enforcement: `available-tools(permission-id)` and the resource/KPI/register checks — one enforcement path used by MCP, chat, ingest, and (potentially) device-initiated actions.

## Data structures / schemas
TODO:
- Descriptor + function split: descriptor (data, runtime-editable) + function (code, deploy to add a dispatch case); `:type`/`:id` as the dispatch key.
- Permission descriptor: `:permission/id`, `:permission/label`, `:permission/allow {:tools :resources :kpis :register}`, `:permission/expires`, and `:permission/tenant-id` (ADR-013/026 addendum — binds a session to one tenant; the session scopes `site-id` through its queries).
- Sessions/connections carry `:session/permission-id` / `:connection/permission-id`; device connections resolve it from the device registry (ADR-020), LLM sessions at establishment.
- `config_history` audit trail (versioned rows, not full event sourcing).
- The `:register` scope: what permission sets may author which descriptor kinds.

## Sequences / flows
TODO:
- Adding a capability: new function in registry (deploy) + new descriptor kind (optional); new descriptor instance is a config write (no deploy).
- Permission resolution at session/connection establishment → tool list filtered before send → runtime never refuses.
- Temporary elevated permissions (technician visit): config entry with `:permission/expires`, enforced at every request.
- Cross-tenant isolation locally: `:permission/tenant-id` selects the datasource a session may touch; in cloud it sets the row-level-isolation variable (ADR-008 mechanism; binding per ADR-026). Within a tenant the session filters `site_id` (and `device-serial`) freely; `tenant_id` is never a query field.

## Invariants & error modes
TODO:
- Capability is by permission-id, never by interface type — granting access once works on MCP, chat, and ingest.
- The LLM never sees tools it cannot call — filtering is pre-send, no runtime "you can't do that."
- `:permission/expires` is checked on every request, not just at session start.
- `:all` as a permission value must not be mistaken for a set containing `:all` — careful handling in enforcement.
- A session scoped to tenant `"home"` can never query another tenant's data — enforced at `open-store` (the `TenantStore` clients are tenant-scoped, 02), not left to individual queries or domain code. Cross-site queries within a tenant are normal (filter `site_id`).

## Open / deferred
- Authentication (how a session *gets* its permission-id) — device connections decided in ADR-020; the human/LLM-session auth UX flow (in-app settings → `:admin`) is light on detail.
- Migration to explicit per-scope reference grants (ADR-019 Option E) if reference data becomes sensitive.
- Config audit trail → XTDB / event-sourcing if temporal config queries become critical (ADR-008).