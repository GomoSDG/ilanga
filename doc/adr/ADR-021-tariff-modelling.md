# ADR-021: Tariff Modelling — Declarative Descriptor + Registry Calculator

## Status
Accepted

## Context
Calculating the financial value of solar requires knowing what electricity would have cost without it. South African municipal tariffs are not flat rates: the City of Johannesburg (CoJ) residential tariff combines fixed service charges, an inclining-block energy charge (rate increases with monthly accumulated consumption), and per-kWh levies. Other municipalities or tariff classes (TOU, prepaid, commercial) have different structures.

A hardcoded CoJ calculator would need a code change and redeploy for every rate revision (typically annual) and could never support a different tariff without more code. Savings calculations must also be deterministic — the same tariff descriptor applied to the same consumption data must produce the same rand amount every time, with no LLM involvement in the arithmetic.

## Decision

### Tariff as descriptor + calculator, following ADR-005

The tariff descriptor lives in the config store (runtime-editable, no deploy to change rates). The calculator functions live in the code registry (deterministic, deploy to add a new charge type). `:charge/type` on each component is the dispatch key.

**Config store — tariff descriptor:**

```clojure
{:tariff/id       :coj-residential-2026
 :tariff/label    "City of Johannesburg Residential"
 :tariff/currency :zar
 :tariff/charges
 [{:charge/type   :fixed-monthly
   :charge/label  "Service charge"
   :charge/amount 250.00}

  {:charge/type   :inclining-block
   :charge/label  "Energy"
   :charge/blocks [{:block/up-to-kwh 500  :block/rate 2.85}
                   {:block/up-to-kwh 1000 :block/rate 3.40}
                   {:block/above-kwh 1000 :block/rate 4.10}]}

  {:charge/type  :per-kwh
   :charge/label "Network levy"
   :charge/rate  0.18}]}
```

**Code registry — charge calculators:**

```clojure
(def charge-calculators
  {:fixed-monthly   #'solar.tariff/calc-fixed
   :inclining-block #'solar.tariff/calc-inclining-block
   :time-of-use     #'solar.tariff/calc-tou
   :per-kwh         #'solar.tariff/calc-per-kwh})
```

### Calculator contract

Each calculator is a pure function:

```clojure
(calc-charge charge-descriptor billing-data) → charge-result
```

`billing-data` contains what the calculator needs — no global state, no side effects:

```clojure
{:billing/consumed-kwh    625.0   ;; accumulated this billing cycle
 :billing/cycle-start     #inst "2026-06-01"
 :billing/cycle-end       #inst "2026-06-30"
 :billing/readings        [...]   ;; timestamped, needed for TOU
 :billing/days-in-cycle   30}
```

A charge result carries a breakdown for auditability:

```clojure
{:charge/label   "Energy"
 :charge/amount  1847.50
 :charge/detail  [{:block/rate 2.85 :block/kwh 500  :block/amount 1425.00}
                  {:block/rate 3.40 :block/kwh 125  :block/amount  425.00}]}
```

Total bill = `(reduce + (map :charge/amount (map calc-charge charges billing-data)))`.

### Inclining-block needs BillingCycle

The inclining-block calculator requires accumulated consumption for the billing cycle, not just a day's readings. This is why the `BillingCycle` entity exists (ADR-002): it tracks `:billing/consumed-kwh` incrementally so the calculator can determine which block(s) apply.

When a day closes (`:day-complete` signal), the day's grid consumption is added to the current `BillingCycle`. The month-end signal closes the cycle. The calculator receives the cycle's accumulated total, not raw day readings.

### Savings = counterfactual, same calculator

Solar savings are not a separate formula. They are the difference between two calculator runs on the same tariff:

```clojure
(defn calc-savings [tariff billing-data]
  (let [actual       (calc-tariff tariff billing-data)
        counterfactual (calc-tariff tariff
                         (assoc billing-data
                                :billing/consumed-kwh
                                (:billing/consumed-kwh-without-solar billing-data)))]
    (- (:tariff/total counterfactual)
       (:tariff/total actual))))
```

`:billing/consumed-kwh-without-solar` = actual grid consumption + solar self-consumption, where solar self-consumption = solar produced − solar exported. No separate savings logic — same tariff model, different input.

**Assumption — no export credit in this savings figure.** The counterfactual values solar only by *avoided import*: every self-consumed kWh is a kWh the home did not buy from the grid. Exported kWh (sold back under a feed-in tariff) are *not* credited here — they are valued separately, if at all, because most CoJ residential tariffs pay nothing for export. Under a strict no-export assumption (all solar is self-consumed, `solar-exported = 0`), `consumed-kwh-without-solar = consumed + solar-produced`. If export is later credited, it is added as its own line item, never folded into `consumed-kwh-without-solar` (which would double-count).

### Site is assigned one active tariff

The `Site` config (ADR-002) carries `:site/tariff-id`, pointing to the active tariff descriptor. A tariff change (annual rate revision) = update the descriptor in the config store + write a new descriptor for the new period if historical comparison is needed. Old descriptors are retained so past BillingCycles can be recalculated correctly.

### New tariff, no code. New charge type, deploy.

| Change | Action needed |
|---|---|
| Rate revision (same structure) | Update descriptor in config store — no deploy |
| New municipality (same charge types) | New descriptor — no deploy |
| New charge type (e.g. demand charge) | New calculator in registry — deploy |
| Tariff class switch (e.g. residential → TOU) | New descriptor with TOU components — no deploy |

## Rationale
- Rates change annually without a code change; structure (charge types) changes rarely
- Separating descriptor (rates/thresholds) from calculator (logic) means a developer reviews code, a user edits rates
- Counterfactual savings from the same calculator eliminates a class of inconsistency bugs where a separate "savings formula" drifts from the billing formula
- Breakdown in charge results makes every bill auditable — not just a number, but which blocks were applied and at what rate
- BillingCycle as the accumulation entity keeps the calculator stateless and pure; the state management is explicit and separate

## Consequences
- (+) Rate revisions are config store edits — no deploy, no code review
- (+) Multiple tariffs (different municipalities, historical rates) coexist as descriptors; system points to its active one
- (+) Savings and billing use one model — they cannot diverge
- (+) Charge breakdowns are auditable at the component and block level
- (+) New charge type (demand, capacity) is a new calculator function — predictable extension path
- (-) Inclining-block calculator depends on BillingCycle accumulated state being correct — a missed reading or skipped day introduces error into the block assignment
- (-) Historical recalculation against old rates requires retaining old descriptors — no automated cleanup of superseded tariff descriptors
- (-) TOU calculator needs timestamped readings at a fine enough granularity to assign each kWh to a time slot — depends on inverter sampling interval being shorter than TOU slot boundaries
