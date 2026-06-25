# ADR-011: Period as Combinable Aggregate (Monoid)

## Status
Accepted

## Context
The system needs to answer questions over arbitrary time windows: this month, last quarter, this year, rolling 30 days. Re-scanning raw readings for every such query is expensive and unnecessary. But pre-computing every possible window is impractical.

The question is: can larger periods be derived from smaller ones without re-scanning raw data?

## Decision
Period is a monoid: two Periods can be merged into a larger Period using `merge-periods`. This means:

```
days      → month  (merge-periods over day summaries)
months    → year   (merge-periods over month periods)
months    → quarter (merge-periods over 3 month periods — derived, not stored)
days      → rolling-30 (merge-periods over last 30 day summaries)
```

**Stored periods**: `day`, `month`, `year` — these are pre-computed and persisted.
**Derived periods**: `quarter`, `rolling-30`, `custom` — computed on demand by folding stored periods.

Each KPI carries a `:kpi/combine` strategy that tells `merge-periods` how to combine it:

| Strategy | Used for |
|---|---|
| `:sum` | energy-kwh, grid-import-kwh, co2-avoided-kg, savings-net |
| `:weighted-avg` | final-yield, capacity-factor, self-sufficiency-rate |
| `:max` | peak-power-w, string-divergence-max |
| `:min` | worst-day-energy |
| `:recompute` | trend fields, health-status — cannot be derived from sub-periods |

Weighted averages use `:kpi/weight-by :days-with-data` not `:day-count` — if a month has 10 days of missing data, the average is over 20 days, not 30.

**Trend fields** (`:string-divergence-trend`, `:health`, `:health-reasons`) are marked `:recompute`. `merge-periods` sets them to `nil`. A separate pass fills them by examining the sequence of sub-period values.

```clojure
(defn merge-periods [p1 p2]
  {:period/from      (earlier (:period/from p1) (:period/from p2))
   :period/to        (later   (:period/to p1)   (:period/to p2))
   :period/day-count (+ (:period/day-count p1) (:period/day-count p2))
   :period/days-with-data (+ (:period/days-with-data p1) (:period/days-with-data p2))
   :period/energy-kwh (merge-kpi + p1 p2 :period/energy-kwh)
   :period/final-yield (merge-kpi weighted-avg p1 p2 :period/final-yield)
   ;; trend fields deferred
   :period/string-divergence-trend nil})
```

## Rationale
- "What was my H1 self-sufficiency?" requires no raw data scan — merge two quarter periods (or six month periods)
- `:days-with-data` as the weight denominator is critical: a simple average of monthly averages is wrong if months have different numbers of valid readings
- Trend fields genuinely cannot be derived from sub-period summaries alone — they require the sequence. Making this explicit (`:recompute`) prevents silent wrong answers
- Storing only day, month, and year minimises storage while keeping all common queries efficient

## What Cannot Be Combined
- `:best-day` and `:worst-day` — use `max-by` and `min-by` on the entity reference
- `:first-sun-at` / `:last-sun-at` — take min and max respectively
- Trend direction (`:improving`, `:stable`, `:worsening`) — requires examining the sequence, not just two endpoints

## Consequences
- (+) Quarter, rolling-30, and custom windows are derived from stored months and days — no raw scan needed
- (+) Weighted averages across unequal windows are correct
- (+) `:kpi/combine` is declarative on the KPI definition — merge logic is generic
- (-) `:recompute` fields require a second pass after merging — two-phase computation
- (-) `merge-periods` must handle unavailable KPIs correctly — do not average `nil` values as zero
- (-) Period storage grows: one row per day, one per month, one per year — acceptable for a home system
