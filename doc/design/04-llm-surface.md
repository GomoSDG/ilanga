# 04 — LLM Surface: Authoring, Validation, Interpretation

**Status:** Stub — skeleton only; implementation detail pending.

## Purpose & scope
The frontend of the compiler — the contract surface between the LLM and the deterministic engine. Two LLM interfaces (MCP + internal chat) sharing one registry/engine/permission system; two modes (registration authors descriptors, analysis interprets pre-assessed data); the three DSLs the LLM authors in (predicates/formulas, templates) and the context-package contract that flows results back. The LLM never executes. **Excludes** the permission descriptors themselves (05) and the engine's execution of authored programs (03).

## Governing ADRs
- ADR-012 MCP and internal chat interfaces — Accepted
- ADR-014 LLM as registrar and analyst — Accepted
- ADR-015 Data expression DSLs (predicates & formulas) — Accepted
- ADR-016 Template DSL — Accepted
- ADR-017 Context package contract (engine → LLM) — Accepted

## Interfaces
TODO:
- The two interfaces (MCP, internal chat) and what they share (registry/engine/permissions — capability is by permission-id, not by interface).
- Registration mode: LLM authors descriptors (`:rule/condition`, `:kpi/available?`, templates) validated at write time (Malli + scope + field-existence) before hitting the config store.
- Analysis mode: LLM receives a context package and interprets pre-assessed KPIs/incidents — the engine pre-computes `:kpi/assessment`; the LLM reads it and generates language from it, never recomputes values.
- The `:register` action scope: what the LLM may author (rules, incidents, tariffs, KPIs) vs what it may not (hardware mappings, tariff *activation*, system binding).

## Data structures / schemas
TODO:
- Predicate DSL `[:op & args]` — `:and :or :not :eq :neq :gt :lt :gte :lte :present :absent :in :between :true? :false?`; recursive Malli `::predicate-expr`.
- Formula DSL — `:add :sub :mul :div :safe-div :min :max`; `:safe-div` returns nil (= unavailable, ADR-010) on zero divisor; recursive `::formula-expr`.
- Template DSL — `{namespace/field}` and `{namespace/field:unit}`; `{{`/`}}` literal braces; missing field → whole template renders `{:template/status :unavailable :template/missing [...]}`.
- Context package (ADR-017): full ADR-010 KPI map, fully-recomputed Periods, `:kpi/assessment` present or nil+note, active Incidents + `:season`, `:package/version` + `:package/as-of`; Malli `::context-package` checked before send; failure → "analysis unavailable: data incomplete."
- The single pure `eval-expr` (no `eval`) — the one whitelisted evaluator.

## Sequences / flows
TODO:
- Registration path (ADR-014 step 8): LLM proposes descriptor → Malli structural validation → scope validation (`validate-scope`) → field-existence (entity schema) → write to config store with audit, or structured error back to LLM.
- Analysis path: engine emits context package (`:kpi/assessment` pre-computed) → Malli check → send to LLM → LLM reads assessments and generates language → report/action; the LLM adds interpretation, not computed values.
- Extractor registry: each DSL registers a function that walks its forms and yields field refs, so a new DSL opts into scope validation without editing the validator.
- Recompute-failure conditions (ADR-017): transitive incompleteness, no data in window, required input unavailable, computation error — incomplete-but-flagged is sendable; incomplete-and-silent is rejected.

## Invariants & error modes
TODO:
- The LLM is never in the hot path and never executes — it authors and interprets only.
- `:safe-div` → unavailable (not NaN, not nil-by-accident); availability is always explicit.
- A validated descriptor is guaranteed to have its referenced data at runtime (drift-free by construction, ADR-019).
- Tariff *activation* (`:site/tariff-id`) and hardware mappings are outside `:register` scope — financial-critical and protocol-critical state stays human/developer-controlled.
- Context-package schema failure degrades gracefully to "analysis unavailable" rather than sending partial data.

## Open / deferred
- Prompt templates catalogue and how the LLM discovers/selects them.
- The full `:register` allow-list boundary (what else the LLM may not author).
- Analysis-mode rate/turn limits and how `:kpi/assessment` is stored/audited.