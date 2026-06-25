# ADR-009: KPI Units as First-Class Registry Entries

## Status
Accepted

## Context
KPIs have units. Without a structured unit model, units become ad-hoc strings, display logic is scattered, and the LLM has no consistent vocabulary for describing measurements. Auto-scaling (kWh → MWh above 1000), consistent decimal places, and display direction (higher is better / lower is better) all need to be defined somewhere.

The key insight: `:good-when` (higher/lower/stable) is a property of the KPI, not the unit. The unit `:pct` is used for both `self-sufficiency-rate` (higher is better) and `string-divergence-pct` (lower is better). Conflating unit and direction produces wrong display logic.

## Decision
Units are registered descriptors with display rules:

```clojure
{:unit/id          :kwh-per-kwp
 :unit/label       "kWh/kWp"
 :unit/type        :specific-yield
 :unit/display     {:decimal-places 2 :suffix "kWh/kWp"}
 :unit/auto-scale  nil}

{:unit/id          :ratio
 :unit/type        :dimensionless
 :unit/range       [0.0 1.0]
 :unit/display     {:decimal-places 1 :suffix "%" :scale-factor 100}}
  ;; stored as ratio 0-1, displayed as percentage

{:unit/id          :zar
 :unit/type        :currency
 :unit/iso-4217    "ZAR"
 :unit/display     {:decimal-places 2 :prefix "R"}}

{:unit/id          :kg-co2
 :unit/type        :mass-co2
 :unit/display     {:decimal-places 1 :suffix "kg CO₂"}
 :unit/auto-scale  [{:above 1000 :convert-to :t-co2 :factor 0.001}]}

{:unit/id          :pct-per-hour
 :unit/type        :rate
 :unit/numerator   :pct
 :unit/denominator :hours
 :unit/display     {:decimal-places 2 :suffix "%/hr"}}
```

`:good-when` lives on the KPI definition, not the unit:

```clojure
{:kpi/id        :string-divergence-pct
 :kpi/unit      :pct
 :kpi/good-when :lower}   ;; lower divergence = better

{:kpi/id        :self-sufficiency-rate
 :kpi/unit      :ratio
 :kpi/good-when :higher}  ;; higher self-sufficiency = better
```

## Registered Units
| ID | Type | Display |
|---|---|---|
| `:w` | power | W |
| `:kw` | power | kW |
| `:wh` | energy | Wh |
| `:kwh` | energy | kWh |
| `:mwh` | energy | MWh |
| `:kwh-per-kwp` | specific-yield | kWh/kWp |
| `:ratio` | dimensionless | % (×100) |
| `:pct` | dimensionless | % |
| `:hours` | time | hrs |
| `:zar` | currency | R |
| `:zar-per-kwh` | currency-rate | R/kWh |
| `:kg-co2` | mass-co2 | kg CO₂ |
| `:t-co2` | mass-co2 | t CO₂ |
| `:pct-per-hour` | rate | %/hr |
| `:v` | voltage | V |
| `:a` | current | A |
| `:hz` | frequency | Hz |
| `:celsius` | temperature | °C |
| `:boolean` | boolean | yes/no |

## Rationale
- Unit descriptors are the single source of truth for display rules — no scattered formatting logic
- Auto-scaling is declarative — the display layer applies it without special cases
- LLM uses unit IDs (`:kwh-per-kwp`) not free strings — consistent vocabulary across registration and analysis
- `params->json-schema` conversion for MCP/Anthropic tool definitions can derive type constraints from unit descriptors

## Consequences
- (+) Consistent display across dashboard, chat responses, and reports — all derive from the same unit descriptor
- (+) LLM registration uses validated unit IDs — typos caught by Malli at write time
- (+) Auto-scaling defined once — no ad-hoc "if value > 1000 show MWh" logic scattered in UI
- (-) New unit types require a registry entry — deploy needed
- (-) `:ratio` vs `:pct` distinction (stored as 0-1 vs 0-100) must be consistently applied — mixing them produces wrong calculations
