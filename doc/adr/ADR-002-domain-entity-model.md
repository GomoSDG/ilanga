# ADR-002: Domain Entity Model

## Status
Accepted

## Context
A solar home system involves multiple distinct concerns: raw telemetry, daily production, overnight battery performance, long-term battery health, time-window trends, domain events, electricity pricing, and monthly billing state. A flat time-series captures measurements but loses the domain structure needed for meaningful KPI computation and lay-person communication.

Each entity must answer a different question:

| Entity | Answers |
|---|---|
| Reading | What did the inverter report right now? |
| Draft | What has today looked like so far? |
| Day | What did solar give me today? |
| Overnight | Did the battery cope last night? |
| Cycle | Is the battery healthy long-term? |
| Period | What does a window of time tell me? |
| Site | What is installed at this site and how is it configured? |
| Incident | What happened and is it resolved? |
| Tariff | What are the electricity rate rules? |
| BillingCycle | Where am I in the current billing month? |
| Reconciliation | Does the calculated bill match the actual municipal bill? |

## Decision
Eleven entities in two groups:

**Temporal entities** (what happened over time):
- `Reading` — raw inverter telemetry, append-only, never mutated
- `Draft` — in-progress day, mutable, replaced on `:day-complete` signal
- `Day` — finalized solar day with all computed KPIs
- `Overnight` — battery window from last-sun to next-first-sun
- `Cycle` — one Day paired with its following Overnight; the battery health unit
- `Period` — aggregate over any window of Days, Overnights, and Cycles

**Supporting entities** (context and reference):
- `Site` — site configuration (what is installed at this location), versioned over time
- `Incident` — domain event with lifecycle: soiling, fault, grid-outage, maintenance
- `Tariff` — electricity rate structure descriptor, versioned
- `BillingCycle` — accumulated monthly grid consumption state; resets on month boundary
- `Reconciliation` — engine-computed comparison of the calculated bill (ADR-021) against the actual municipal bill (ADR-025); stored as a first-class artifact (introduced in ADR-025)

## Rationale
- **Overnight as first-class entity**: the battery's overnight performance cannot be derived from a single Day — it spans the seam between two calendar days and has its own KPIs (lasted?, discharge rate, grid fallback)
- **Cycle as battery health unit**: pairing a Day's charge with its Overnight's discharge gives round-trip efficiency and depth of discharge — the signals needed to track battery SOH degradation over hundreds of cycles
- **Period folds over all three**: Days, Overnights, and Cycles each contribute different KPIs to a Period aggregate; treating them separately keeps aggregation logic clean
- **Incident as first-class**: soiling, shading, and faults are domain facts with lifecycle — not just log entries. They annotate KPI dips with meaning and feed health scoring
- **Tariff + BillingCycle separated**: Tariff is a slow-changing rate structure descriptor. BillingCycle is fast-changing accumulated state. Keeping them separate makes savings calculation correct: `cost(consumed + solar_produced) - cost(consumed)`
- **Site is versioned**: adding panels or replacing the battery changes installed kWp. Without versioning, long-term yield trends break at the reconfiguration date

## Consequences
- (+) Each entity has a clear, bounded responsibility and answers a specific question
- (+) LLM analysis receives a well-structured context package — entities are self-describing namespaced maps
- (+) KPI computation targets are clear — each entity has a defined set of KPIs that belong to it
- (+) Incident lifecycle enables correlation between anomalies and KPI dips over time
- (-) More entities than a flat model — more surfaces to keep consistent
- (-) Overnight spans two calendar dates — queries must account for this boundary
- (-) BillingCycle accumulation depends on data completeness — gaps in readings undercount grid import
