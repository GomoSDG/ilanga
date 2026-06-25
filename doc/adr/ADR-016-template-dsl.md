# ADR-016: Template DSL (Entity Interpolation)

## Status
Accepted

## Context
ADR-006 introduces `:template "...{overnight/ran-out-at}..."` for dynamic action params and lists as an open consequence: "template resolution must handle missing entity fields gracefully — define fallback behaviour." Templates are LLM-authored — they appear in action params inside rules the LLM registers (ADR-014 registration mode). The path grammar, missing-field semantics, escaping, and formatting are all undefined. A template passes Malli (it is a string) yet can resolve to an empty or wrong notification at runtime — "Battery depleted at " with the field silently missing.

## Decision
Define a minimal, strict interpolation grammar. Templates interpolate *values only*; logic belongs in predicates/formulas (ADR-015) or in the action function.

**Grammar:**
- `{namespace/field}` — single field of the entity in scope, e.g. `{overnight/ran-out-at}`.
- `{namespace/field:unit}` — optional format suffix drawn from the unit registry (ADR-009), e.g. `{day/energy-kwh:kwh}` formats via the `:kwh` descriptor. The dashboard and the template therefore use one source of truth.
- No nesting, no expressions, no arithmetic.
- `{{` and `}}` are literal braces.

```clojure
{:action/params {:title "Battery ran out overnight"
                 :body  [:template "Depleted at {overnight/ran-out-at} after {overnight/duration-hours:hours}"]}}
```

**Missing-field policy** (chosen over silent partial substitution): if any referenced field is missing or unavailable (`:kpi/available? false`, ADR-010), the *whole template renders as unavailable* rather than producing a partial string. The action receives:

```clojure
{:template/status   :unavailable
 :template/missing  [:overnight/ran-out-at]
 :template/rendered nil}
```

The action then decides whether to skip (recommended) or substitute. Failures are visible and decision-explicit, never silent.

**Validation:**
- Malli schema `::template` parses the string and checks every `{...}` is a registered field path plus an optional registered unit, at write time. Unknown path or unit → Malli error returned to the LLM (ADR-014 step 8).
- The entity in scope is the action's triggering entity (same rule as ADR-015). Field paths are cross-validated against that scope entity type per ADR-019 — a `{overnight/ran-out-at}` path in a `:day` rule's action fails at write time with an actionable error, not at render time as an empty string.

## Rationale
- A strict path grammar means the LLM cannot author a path that silently resolves to nothing — unknown paths fail at registration.
- Delegating formatting to the unit registry means templates never duplicate display logic; `{savings-net:zar}` and the dashboard both use the `:zar` descriptor.
- Whole-template unavailability (not partial empty) prevents the common silent bug and keeps the action in control of failure, consistent with ADR-010's "never silent nil" principle.
- No expressions in templates keeps the separation clean: logic in predicates/formulas, values in templates.

## Consequences
- (+) LLM-authored templates are validated against registered fields and units at write time.
- (+) Formatting is consistent with dashboards (single unit source of truth).
- (+) Missing-field failures are explicit and action-controllable, never silent.
- (-) Templates cannot do even trivial arithmetic ("{a} of {b}"); that must be a precomputed KPI or a formula (ADR-015) surfaced as a field.
- (-) Action functions must handle `:template/status :unavailable` rather than assuming a string param.
- (-) The field path registry must exist and stay consistent with entity schemas (ADR-002) — a stale path fails validation, which is correct but requires the registry to be maintained.