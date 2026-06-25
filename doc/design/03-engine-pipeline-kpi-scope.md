# 03 — Engine: Pipeline, KPIs & Scope

**Status:** Stub — skeleton only; implementation detail pending.

## Purpose & scope
The deterministic core. Signal-driven declarative pipeline dispatch, the KPI registry with first-class units and explicit availability, Period as a combinable aggregate, and the declared evaluation contexts that make scope validation drift-free by construction. The engine computes; the LLM (04) references precomputed values. **Excludes** the DSL grammars themselves (04) and the config/registry substrate (05) — this doc covers *running* the programs, not authoring them.

## Governing ADRs
- ADR-003 Solar day boundary — Accepted
- ADR-007 Declarative pipeline (signal → computation) — Accepted
- ADR-009 KPI units as first-class registry entries — Accepted
- ADR-010 Explicit KPI availability — Accepted
- ADR-011 Period as combinable aggregate — Accepted
- ADR-019 Entity-scope validation via declared evaluation contexts — Accepted

## Interfaces
TODO:
- Signal types: `:new-reading`, `:day-complete`, `:day-boundary`, `:month-boundary`, `:year-boundary`, `:backfill`, `:late-reading`, `:incident-created`, `:incident-resolved`, `:tick`.
- Pipeline rule descriptor (`{:on signal :compute [keyword…]}`); each `:compute` keyword maps to a code-registry function.
- `::scope-context` declaration: scope entity-type → set of entity types assembled when evaluating in that scope (`{:reading #{:reading :site :tariff :incident}, :overnight #{:overnight :site :tariff :incident}, :cycle #{:cycle :day :overnight :site :tariff :incident}, :period #{:period :site :tariff :incident}, …}`).
- `validate-scope` + the orthogonal field-existence (entity-schema) check — one pass, two concerns.

## Data structures / schemas
TODO:
- KPI descriptor + `:kpi/combine` strategies + `:recompute` for non-derivable fields.
- The availability convention (ADR-010): a KPI is present with `:kpi/available? false` + note, or absent-but-flagged — **never silently nil**. `:safe-div` on zero divisor → unavailable.
- Period as monoid: `merge-periods` and how composites expose merged KPIs (not constituent instances) — the Cycle-vs-Period seam (concrete constituents vs merged KPIs).
- The two-phase `:recompute`: incomplete-but-flagged (`:period/complete? false` + `:period/note`) is valid/sendable; incomplete-and-silent is what the schema rejects.

## Sequences / flows
TODO:
- `:day-complete` → `:day/finalize` → `:overnight/compute` → `:cycle/compute` → `:rules/evaluate` → `:billing-cycle/update` (ordered; order must be respected).
- `:tick` → `:incidents/close-expired` (auto-close Incidents past `:incident/ends-at`, ADR-023).
- `:late-reading` → `:day/patch` → `:day/recompute-health` → `:period/recompute-affected` (the tricky propagation case).
- Context assembly from `::scope-context` (declaration-driven) — the engine commitment; if it ever goes ad-hoc, ADR-019 reverts to Option A + consistency check.
- KPI computation: pure functions over the data layer; `:kpi/assessment` left nil for the LLM (04) to fill in analysis mode.

## Invariants & error modes
TODO:
- Determinism: same descriptors + same data → same KPIs, always; the LLM is never in this path.
- Default-deny scope (ADR-019): a descriptor references only entities in its `::scope-context` set or KPIs of its scope entity (incl. merged KPIs for composites).
- Scope check (is this entity in scope?) is orthogonal to field-existence (is this field real?) — both run in the same registration pass.
- No temporal neighbours: an Overnight context does not include the preceding Day; lift cross-entity needs to explicit computed KPIs.
- `:late-reading` patching must propagate to affected Periods without corrupting sibling data.

## Open / deferred
- `:tick` interval (default ~1 min) and the full set of `:tick` consumers (only `:incidents/close-expired` defined so far — draft staleness / missed-reading detection may also ride it).
- Eager assembly vs lazy fetching tradeoff (accepted at home-system scale).