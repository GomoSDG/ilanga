# ADR-010: Explicit KPI Availability — Never Silent Nil

## Status
Accepted

## Context
Not all KPIs can always be computed. Performance Ratio requires irradiance data that this system does not have (no pyranometer). Savings require a configured tariff. Some KPIs require a full day of data before they are meaningful. If unavailability is represented as `nil`, dashboards silently break, period averages include missing values as zero, and the LLM has no way to explain what is missing and why.

## Decision
Every KPI value is a map that always carries availability metadata:

```clojure
;; Available
{:kpi/value       2.49
 :kpi/unit        :kwh-per-kwp
 :kpi/available?  true
 :kpi/assessment  :typical
 :kpi/combine     :weighted-avg
 :kpi/weight-by   :days-with-data
 :kpi/note        nil}

;; Unavailable
{:kpi/value       nil
 :kpi/unit        :ratio
 :kpi/available?  false
 :kpi/assessment  nil
 :kpi/combine     :weighted-avg
 :kpi/weight-by   :days-with-data
 :kpi/note        "Requires irradiance sensor or clear-sky model"}
```

KPI definitions declare their availability condition:

```clojure
{:kpi/id         :performance-ratio
 :kpi/available? [:present :day/irradiance-kwh-m2]
 :kpi/note       "Requires irradiance sensor or clear-sky model"}

{:kpi/id         :savings-net
 :kpi/available? [:present :tariff/id]
 :kpi/note       "Requires a configured tariff"}
```

Period aggregation skips unavailable KPIs — it does not average them as zero. The period itself carries `{:kpi/available? false}` for any KPI where insufficient days had data.

`:kpi/assessment` is computed deterministically by the engine from seasonal benchmarks and is `nil` when the value is unavailable.

## Rationale
- Silent `nil` breaks averages, charts, and period rollups in ways that are hard to detect
- Explicit unavailability lets the LLM explain what is missing: "Performance Ratio is not available — this requires an irradiance sensor or a clear-sky model"
- The dashboard can render an "unavailable" state with a reason rather than a blank or zero
- During period aggregation, `:days-with-data` tracks only days where the KPI was available — denominators are correct

## Consequences
- (+) No phantom zeros in period averages — unavailable KPIs are excluded from aggregation
- (+) LLM can explain what is missing and why — `:kpi/note` is always a complete sentence
- (+) Dashboard always has a renderable state for every KPI — either a value or an explained absence
- (+) `:kpi/assessment` is always either a valid keyword or `nil` — never a comparison against a nil value
- (-) Every KPI value is a map — more verbose than a scalar; callers must always destructure
- (-) Period aggregation must handle the case where no days had an available value for a given KPI
