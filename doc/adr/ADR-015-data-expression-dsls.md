# ADR-015: Data Expression DSLs (Predicates and Formulas)

## Status
Accepted

## Context
Several accepted ADRs already rely on data-native expressions that the engine evaluates, but none defines their grammar or semantics:

- ADR-007 rule conditions: `:rule/condition [:eq :overnight/lasted? false]`
- ADR-010 KPI availability: `:kpi/available? [:present :day/irradiance-kwh-m2]`
- ADR-001 KPI formulas: `[:div :day/energy-kwh :site/installed-kwp]`

ADR-001 treats these as "just Clojure data — no parser needed." That is true for *authoring* but not for *validation or safe evaluation*. Predicates are LLM-authored into the config store (rule conditions, KPI availability); formulas are developer-authored in the registry. Both are evaluated by the engine. Without a defined grammar and Malli schema:

- An LLM-authored predicate with a typo or wrong operand shape is structurally a valid vector, so it passes Malli but fails or misbehaves at rule-fire time.
- A formula referencing a non-existent field is not caught until evaluation.
- Each callsite may hand-roll interpretation, risking inconsistency and accidental use of `eval` / `read-string` on untrusted structure — the exact thing ADR-005 forbids at the config-store boundary.

## Decision
Define two operator families over one shared `[:op & args]` form, validated by Malli and evaluated by a single whitelisted, side-effect-free evaluator.

**Predicate operators** (boolean; LLM-authored in config store):
`:and :or :not`, `:eq :neq`, `:gt :lt :gte :lte`, `:present :absent`, `:in :between`, `:true? :false?`

**Formula operators** (arithmetic; developer-authored in registry):
`:add :sub :mul :div :safe-div :min :max`

Operands:
- A namespaced keyword (`:day/energy-kwh`, `:overnight/lasted?`, `:kpi/...`) is a field/KPI reference, resolved against the entity in scope.
- A non-namespaced keyword or literal is a constant / enum value.

`:safe-div` returns `nil` (→ ADR-010 unavailable) on a zero denominator instead of throwing — division-by-zero becomes a first-class *unavailable* result, not an exception.

```clojure
;; Predicate — LLM-authored rule condition
:rule/condition [:and [:eq :overnight/lasted? false]
                        [:gte :overnight/duration-hours 8]]

;; Predicate — LLM-authored KPI availability
:kpi/available? [:present :day/irradiance-kwh-m2]

;; Formula — developer-authored KPI computation
:kpi/formula [:div :day/energy-kwh :site/installed-kwp]
```

## Shared Rules
- **Operator registry**: `{:op/id :eq :op/arity 2 :op/category :predicate :op/eval fn}`. Unknown operator → Malli error at write/load time.
- **One evaluator**: a single pure function `eval-expr [expr context]` dispatching on the first element. No `eval`, no `read-string`, no arbitrary invocation. The engine never executes untrusted structure — only whitelisted operators over resolved values.
- **Malli schemas**: recursive `::predicate-expr` and `::formula-expr` schemas, used to validate `:rule/condition`, `:kpi/available?`, and registry formula definitions.
- **LLM descriptor**: a resource `solar://registry/expr-grammar` listing operators, arities, and operand rules, so the LLM authors well-formed expressions and corrects on Malli error (ADR-014 step 8).
- **Scope**: the entity in scope for a rule condition is the triggering entity; for `:kpi/available?` it is the entity the KPI belongs to (e.g. the Day).

## Rationale
- Unifies three ad-hoc uses under one validated, safe evaluator — no per-callsite interpretation.
- LLM-authored predicates fail fast at write time with a structured Malli error, not silently at fire time.
- `:safe-div` aligns division-by-zero with ADR-010 availability instead of a thrown exception.
- The grammar is exposed to the LLM as data, closing the gap of an embedded DSL accreting without a definition.
- New operators are a registry entry (deploy) — the operator vocabulary stays controlled, preserving the ADR-005 boundary.

## Consequences
- (+) Predicates and formulas are validated, versionable, and LLM-correctable.
- (+) No `eval` anywhere — untrusted structure is never executed, only resolved-and-dispatched.
- (+) One evaluator means rule conditions and KPI formulas compute consistently.
- (-) Recursive Malli schemas for expressions are more complex than scalar schemas.
- (-) Conditions not expressible in the fixed operator set require a new operator (deploy) — consistent with ADR-005 but a real ceiling.
- (-) Operand resolution must define the entity in scope per callsite. Structural Malli validates that a path is well-formed but not that it is valid *for the scope entity* (e.g. `:day/energy-kwh` in a `:rule/on :overnight` condition). That cross-validation is prescribed by ADR-019 (entity-scope validation at write time); without it, wrong scope produces wrong values, not a crash.