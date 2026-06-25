# ADR-017: Context Package Contract (Engine → LLM)

## Status
Accepted

## Context
ADR-014 defines the analysis context package the LLM receives. ADR-010 defines KPI availability metadata and ADR-011 defines Period `:recompute` semantics. The interaction between these is unspecified:

- ADR-011 sets trend fields (`:string-divergence-trend`, `:health`) to `nil` until a second recompute pass. If the context package is assembled before that pass completes, the LLM receives a Period with `nil` trends — and, per ADR-010, `nil` is exactly what the system forbids as a silent state.
- ADR-010 marks unavailable KPIs `{:kpi/available? false}`, but nothing guarantees the package always carries the *full* KPI map rather than a bare scalar.
- The package has no version or as-of stamp, so when entity shapes evolve (ADR-002) there is no way to detect that the LLM reasoned over a stale shape.

The real risk of read-only analysis (ADR-014) is not data corruption — that is already prevented — it is **confabulation over incomplete data**: the LLM invents an explanation for a gap rather than reporting the gap.

## Decision
The context package is a versioned, validated contract, not ad-hoc assembly. The engine asserts the contract before handing the package to either interface (ADR-012).

**Guarantees:**
1. Every KPI value is the full ADR-010 map (`:kpi/value :kpi/unit :kpi/available? :kpi/assessment :kpi/note`). No bare scalars; no silent nils.
2. Every Period is fully recomputed — `:recompute`-marked fields (ADR-011) are populated, never left `nil`. If a recompute pass cannot complete, the Period is `{:period/complete? false :period/note "..."}`, and the LLM is told why.
3. `:kpi/assessment` is always present or explicitly `nil` with a `:kpi/note` — never a comparison against a missing value.
4. Incidents are the active set with lifecycle state; the package names `:season` (ADR-002) so seasonal benchmarks are interpretable.
5. A `:package/version` and `:package/as-of` stamp, so the LLM and logs know what was reasoned over.

**Validation:** a Malli schema `::context-package` checked before send. A package that fails the contract is not sent — the interface returns "analysis unavailable: data incomplete" rather than handing the LLM partial state to confabulate over.

## Recompute Failure Conditions
A Period is emitted with `:period/complete? false` plus a `:period/note` when any of:
- **Transitive incompleteness** — a constituent Day/Overnight/Cycle is itself incomplete or has missing readings for the window.
- **No data in window** — `:days-with-data` (ADR-011) is zero, so no constituent has any available value.
- **Required input unavailable** — a `:recompute`-marked field's source values are unavailable (ADR-010 `:kpi/available? false`) for every constituent in the window.
- **Computation error** — a `:recompute` pass throws or returns nil for a field that cannot be derived from sub-period summaries (ADR-011 `:recompute`).

A recompute failure is the *expected, explainable* way an incomplete Period arises: the Period is still emitted with its available sub-period KPIs intact, only the recompute/trend fields flagged incomplete. The contract violation that `::context-package` rejects is different — a Period carrying `nil` recompute fields *without* the `:period/complete? false` flag. In other words: incomplete-but-flagged is valid and sendable; incomplete-and-silent is what the schema catches.

## Rationale
- Pins the "distilled, not raw" loop-back of ADR-014 as an enforced guarantee, not an assumption.
- Closes the ADR-011 `:recompute`-nil gap: the LLM never sees a half-built Period.
- A failed contract is a loud, correctable failure — the LLM declines rather than invents, which is the correct behaviour for read-only analysis.
- The version stamp makes stale-client reasoning detectable when entity shapes evolve.
- A future interface (ADR-012's Slack bot) gets the same guaranteed package.

## Consequences
- (+) The analysis loop's input is guaranteed well-formed; the LLM's job is interpretation, not gap-filling.
- (+) Contract violations surface before the LLM sees them — no confabulation over incomplete data.
- (+) Stable interface for future interfaces.
- (-) Context construction is stricter and slightly slower — every send validates against `::context-package`. Acceptable for a home system with a small package.
- (-) If a needed KPI is unavailable, the LLM must decline that part rather than estimate — correct, but prompt templates must instruct the LLM to say so.
- (-) `:package/version` introduces a compatibility surface: a bump must be coordinated with prompt templates that assume a given shape.