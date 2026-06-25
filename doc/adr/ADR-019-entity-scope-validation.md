# ADR-019: Entity-Scope Validation via Declared Evaluation Contexts

## Status
Accepted

## Context
ADR-015 (predicates/formulas) and ADR-016 (templates) validate their expressions structurally with Malli: a `[:op & args]` form or a `{...}` interpolation is well-formed. But both reference entity fields by namespaced keyword (`:day/energy-kwh`) or path (`{overnight/ran-out-at}`), and Malli validates only that these are valid *paths*, not that they are valid *for the entity in scope*.

Example (raised in ADR-015 review):
```clojure
{:rule/on        :overnight
 :rule/condition [:gt :day/energy-kwh 10]}
```
This passes Malli — `:day/energy-kwh` is a structurally valid field path — but the rule fires on an Overnight entity, which has no `:day` field in scope. The result is a wrong value or nil, not a crash. ADR-015 acknowledges this ("getting this wrong produces wrong results, not a crash") but prescribes no fix. ADR-016 has the identical root cause: a template `{overnight/ran-out-at}` inside a `:day` rule's action passes structural Malli but fails at render time.

This is a single cross-cutting gap: field references are validated structurally but not against the entity type determined by the descriptor's scope.

## Decision
Validate field references against the engine's **declared evaluation context** — the same set of entities the engine assembles when firing a rule or computing a KPI in that scope. Because the validator and the engine read the same declaration, what validates is exactly what is available at runtime: the validation/runtime drift a separate reachability graph would suffer is eliminated by construction.

### Engine commitment (the real decision)
The engine assembles its evaluation context *from a declaration* (`::scope-context`), not ad-hoc per callsite. This declaration is the single source of truth for "what's in scope." This is what makes the approach cheap and correct, and it is feasible because the system is greenfield. If the engine ever assembles context ad-hoc, the drift-free guarantee is lost and this ADR should revert to a separate reachability graph plus a startup consistency check.

### Scope context declaration
Scope entity-type → set of entity types assembled when evaluating in that scope:

```clojure
{::scope-context
 {:reading   #{:reading   :site :tariff :incident}
  :draft     #{:draft     :site :tariff :incident}
  :day       #{:day       :site :tariff :incident}
  :overnight #{:overnight :site :tariff :incident}                  ;; no preceding Day
  :cycle     #{:cycle :day :overnight :site :tariff :incident}      ;; concrete constituents
  :period    #{:period    :site :tariff :incident}}}                  ;; no constituents — see Aggregates
```

### Two kinds of references, validated separately
- **Entity field refs** (`:day/energy-kwh`, `:overnight/lasted?`): the reference's namespace must be in the scope's context set. `:day/energy-kwh` in a `:rule/on :overnight` rule → `:day` is not in overnight's context → rejected.
- **KPI refs** (`:kpi/final-yield`): resolved against the scope entity's KPI set. Composites include their merged KPIs (ADR-011), so `:kpi/final-yield` is valid on `:period` without `:day` being in context.

Namespace membership is the scope check. **Field existence** is a separate, orthogonal concern: a path whose namespace is in context but whose field does not exist on that entity (e.g. `:day/energy-kw` — a typo) is rejected against the entity schema registry (ADR-002/010) in the same pass. Scope answers "is this entity in scope?"; schema answers "is this field real?" — two clean checks, one pass.

### Reference entities are ambient
`:site`, `:tariff`, and `:incident` are included in every context. They are cross-cutting reference data, not contained by any temporal entity, and not sensitive for a home system. (If sensitivity matters later, migrate to explicit per-scope grants — see Alternatives.)

### Composition is concrete for low-cardinality composites
A Cycle's context includes its one Day and one Overnight as concrete entities — unambiguous and cheap. A Cycle rule may reference `:day/energy-kwh` (the cycle's specific day).

### Aggregates use merged KPIs, not constituent instances
A Period's context does **not** include its constituent Days/Overnights/Cycles — high cardinality, and per-instance fields like `:day/energy-kwh` are not meaningful on an aggregate. A Period rule references `:period/energy-kwh` or merged `:kpi/*`, not `:day/*`. This is the seam where "concrete context" gives way to "merged KPIs on the aggregate," and it reflects a real semantic difference (Cycle vs Period), not an arbitrary limitation.

### Time-direction: self + constituents only, no temporal neighbors
An Overnight context does **not** include the preceding Day, even though the data exists. If an Overnight rule needs Day data, lift it to an explicit `:overnight/preceding-day-*` KPI — engine-computed and auditable, consistent with the "engine computes, LLM references precomputed values" discipline (ADR-014).

### Strictness: allow-list / default-deny
A reference is valid only if its namespace is in the context set or it is a registered KPI of the scope entity (including merged KPIs for composites). Anything else is rejected with a structured error returned to the LLM (ADR-014 step 8): *"Rule is on :overnight; :day/energy-kwh is not in scope. Overnight rules cannot reference Day fields — lift the value to an :overnight KPI if needed."*

### Validation pass
Runs in the registration path (ADR-014) after Malli, before write:

```clojure
(defn validate-scope [descriptor]
  (let [scope-type (scope-of descriptor)                       ;; :rule/on, :kpi/entity, etc.
        ctx        (scope-context scope-type)                  ;; from ::scope-context
        refs       (extract-field-refs descriptor)             ;; DSL-aware: predicates, formulas, templates
        bad-entity (remove #(contains? ctx (entity-ns %)) (entity-refs refs))
        bad-kpi    (remove #(kpi-of? scope-type %) (kpi-refs refs))] ;; incl. merged KPIs for composites
    (when (or (seq bad-entity) (seq bad-kpi))
      {:scope/error      :invalid-field-refs
       :scope/scope-type scope-type
       :scope/invalid    (concat bad-entity bad-kpi)})))
```

`extract-field-refs` is DSL-aware via an **extractor registry**: ADR-015 (predicates/formulas) and ADR-016 (templates) each register a function that walks its forms and yields the references. A new DSL opts into scope validation by registering an extractor, without editing the validator.

## Rationale
- **No drift by construction** — validator and engine share one declaration, so a rule that validates is guaranteed to have its referenced data at runtime. This is the property the review was after; a separate reachability graph only approximates it and needs a consistency check as a partial substitute.
- **Cheap because greenfield** — the engine is built declaration-driven from the start; the cost is the commitment, not a retrofit.
- **The aggregate seam is semantic truth** — Cycle (concrete constituents) and Period (merged KPIs) really are different. The declaration makes the difference explicit instead of letting an abstract reachability graph be naively wrong (`:period → #{:day :overnight}` would imply `:day/energy-kwh` is valid on a Period, which it is not).
- **No temporal neighbors** keeps the context minimal and forces cross-entity needs into explicit, computed, auditable KPIs — on-brand with the engine-computes discipline.
- **Ambient reference data** is pragmatic for a home system; the declaration stays small.
- **Default-deny** matches ADR-005/006/014: the LLM references only what is explicitly in scope.

## Alternatives Considered
- **Flat reachability map (Option A)** — a separate `scope → #{reachable}` graph. Simpler invariant, but can drift from what the engine actually assembles; needs a startup consistency check as a partial substitute. Rejected because greenfield makes declaration-driven assembly cheap and drift-free by construction.
- **Explicit per-scope reference grants (Option E)** — reference entities granted per scope rather than ambient. Tighter and auditable per scope, but many more declarations. Ambient is sufficient while reference data is not sensitive; E is the migration path if it becomes so.
- **Strict isolation (Option F)** — scope = self only, all cross-entity needs become KPIs, composites reach nothing. Cleanest validator, but forces redundant KPIs (`:cycle/day-energy`) when `:day/energy-kwh` is right there. Rejected: composites should see their constituents.

## Consequences
- (+) A validated descriptor is guaranteed to have its referenced data at runtime — no wrong-value-at-fire-time, no empty-template-at-render-time.
- (+) One declaration drives both engine assembly and validation — single source of truth, no drift.
- (+) The Cycle-vs-Period structural difference is explicit, preventing a naive reachability graph from being semantically wrong.
- (+) Future DSLs opt into scope validation by registering an extractor, not by editing the validator.
- (-) **The engine must be declaration-driven for context assembly** — the real cost. Feasible greenfield; a retrofit burden later. If the engine ever assembles context ad-hoc, the drift-free guarantee is lost and this ADR should revert to Option A plus a consistency check.
- (-) Eager assembly: everything in a context is assembled to evaluate any rule in that scope, foreclosing lazy/on-demand fetching. Acceptable at home-system scale.
- (-) The aggregate seam means two sub-mechanisms (concrete context vs merged KPIs); more to specify, though it reflects real semantics.
- (-) Ambient reference data means any scope can see Site/Tariff/Incident — fine while not sensitive; a migration to explicit grants (Option E) if it becomes so.
- (-) Lifting cross-entity needs (e.g. preceding-Day data in an Overnight rule) into KPIs adds KPI-definition work — but it is auditable, computed data, consistent with the architecture.