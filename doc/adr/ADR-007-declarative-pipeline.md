# ADR-007: Declarative Pipeline (Signal → Computation Rules)

## Status
Accepted

## Context
The system reacts to signals — new readings arrive, the solar day completes, a month boundary passes, historical data is backfilled, an incident is recorded. Each signal triggers a set of computations: update the draft, finalise the day, compute overnight, evaluate rules, execute actions, update billing state.

Hardcoding this dispatch logic makes it invisible and inflexible. It also makes it hard to reason about what happens when.

## Decision
Pipeline rules are declarative descriptors in the config store mapping signals to computation sequences:

```clojure
{:pipeline/rules
 [{:on :new-reading    :compute [:draft/update
                                 :signal/detect-solar-events]}

  {:on :day-complete   :compute [:day/finalize
                                 :overnight/compute
                                 :cycle/compute
                                 :rules/evaluate
                                 :billing-cycle/update]}

  {:on :month-boundary :compute [:period/compute-month
                                 :billing-cycle/reset]}

  {:on :year-boundary  :compute [:period/compute-year]}

  {:on :backfill       :compute [:day/recompute
                                 :overnight/recompute
                                 :cycle/recompute
                                 :period/recompute-affected]}

  {:on :incident-created :compute [:day/recompute-health
                                   :rules/evaluate]}

  {:on :tick            :compute [:incidents/close-expired]}   ;; periodic (default ~1 min) — ADR-023 auto-close

  {:on :late-reading   :compute [:day/patch
                                 :day/recompute-health
                                 :period/recompute-affected]}]}
```

Each `:compute` entry is a keyword that maps to a function in the code registry. The engine reads the pipeline rules and dispatches accordingly.

## Signal Types
- `:new-reading` — inverter telemetry received
- `:day-complete` — PV power zero for configured window after being positive
- `:day-boundary` — midnight (used for billing resets only, not day finalisation)
- `:month-boundary` — first reading of a new calendar month
- `:year-boundary` — first reading of a new calendar year
- `:backfill` — historical data loaded for one or more dates
- `:late-reading` — reading arrives for an already-finalised day
- `:incident-created` — new incident recorded
- `:incident-resolved` — incident closed
- `:tick` — periodic clock tick (configurable interval, default ~1 min); drives time-based computation such as closing Incidents past their `:incident/ends-at` (ADR-023)

## Rationale
- The pipeline is visible as data — what happens when is readable without tracing code
- New signal types and new computation steps can be added to config without changing engine dispatch logic
- The sequence within `:compute` is ordered — `:day/finalize` must precede `:overnight/compute`
- Pipeline rules live in config store so they can be updated without deploy (new computation sequences, reordering steps)

## Consequences
- (+) Pipeline behaviour is auditable — the rules are data, not hidden in call stacks
- (+) New signals and computations can be added to the config store without a deploy (as long as the computation keyword is registered)
- (+) Reordering computation steps is a config change, not a code change
- (-) Ordered sequences within `:compute` must be respected — the engine must execute them in declaration order
- (-) New computation keywords still require a function in the code registry — a deploy is needed for genuinely new computation logic
- (-) `:late-reading` handling introduces complexity — patching a finalised Day must propagate to affected Period without corrupting other data
