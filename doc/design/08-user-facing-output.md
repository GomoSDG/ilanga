# 08 — User-Facing Output

**Status:** Stub — skeleton only; implementation detail pending.

## Purpose & scope
The human surface that is **not** the interactive LLM interface: the dashboard, historical views, alerts/notifications, and proactive LLM-report push. **Read-only over precomputed data** — it consumes engine KPIs (`ilanga.domain.days`, `ilanga.domain.kpis`, `ilanga.domain.periods`) and the data layer (`ilanga.domain.*`) directly, or the LLM surface (04) when natural-language interpretation of those facts is wanted. It **never computes**: it is outside the determinism boundary (ADR-014), so its outputs are not part of the same-descriptors-+-same-data → same-KPIs guarantee. **Excludes** the interactive MCP/chat request→response surface (owned by 04) and the KPI computation itself (owned by 03).

### Boundary with 04
- **04** = *interactive* LLM interface: a human issues a request, the LLM responds. MCP/chat request→response.
- **08** = *everything else the human sees*: dashboard, alerts, proactive report push.
- 08 is a **consumer** of 04's analysis when LLM interpretation is wanted; a **direct reader** of `ilanga.domain.*` + engine KPIs when not.

## Governing ADRs
- ADR-014 Determinism boundary — 08 is outside it (read-only, non-deterministic presentation) — Accepted
- ADR-026 Multi-tenant storage model — all reads go through `TenantStore`, filtered by `site_id` — Accepted
- ADR-031 User-facing output surface (TDD-08 governing ADR) — **pending**

## Interfaces

### Reads (all read-only, all through `TenantStore`)
- KPIs / Day / Period views: `ilanga.domain.days/*`, `ilanga.domain.kpis/*`, `ilanga.domain.periods/*` (TDD-03) — `store` + `site-id` arguments, never `tenant-id`.
- Raw readings / history: `ilanga.domain.readings/in-range store site-id from to` (TDD-01/02).
- LLM interpretation (optional): delegated to the 04 surface.

### Outbound surfaces (TODO — to be defined)
- Dashboard (live + historical views) — the first concrete surface (Task #8).
- Alerts / notifications.
- Proactive LLM-report push (consumes 04, scheduled or event-triggered).

## Data structures / schemas
TODO:
- View models for the dashboard (derived from entity schemas, not stored).
- Alert/rule descriptors (per-tenant config, ADR-026) and the trigger→surface path.

## Sequences / flows
TODO:
- Dashboard render: TenantStore → domain reads → view model → surface.
- Alert: engine signal → 08 rule → outbound surface.
- Proactive report: schedule/event → 04 interpretation → 08 push.

## Invariants & error modes
- Read-only: 08 never writes facts; a write path here is a fault.
- All reads go through `TenantStore`, scoped by `site_id`; a tenant-isolation leak via the output surface is a security fault (per TDD-00 error modes).
- Outside the determinism boundary: 08 may degrade gracefully (partial dashboard) but cannot corrupt inside-boundary data; unavailability is data (ADR-010) — a missing KPI renders explicit, never silently nil.
- LLM interpretation is non-deterministic; 08 surfaces it as *interpretation*, never as a stored fact.

## Open / deferred
- ADR-031 (governing ADR for this surface) — pending.
- The concrete dashboard surface and its view models (Task #8).
- Alert/rule descriptor model and trigger semantics.
- Proactive-report scheduling and delivery mechanism.