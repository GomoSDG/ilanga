# ADR-003: Solar Day Bounded by Solar Events, Not Midnight

## Status
Accepted

## Context
A solar day does not align with a calendar day. Production begins at first-sun and ends at last-sun. Battery discharge runs from last-sun until next-first-sun. Forcing these onto midnight boundaries splits meaningful windows and makes overnight duration invisible.

In winter at this site (Johannesburg, UTC+2), first-sun is around 10:25 local and last-sun around 18:31 local — the overnight window is approximately 16 hours. In summer these boundaries shift significantly. Overnight duration itself is therefore a seasonal KPI worth tracking.

## Decision
- **Day**: first-sun → last-sun (solar events, not midnight)
- **Overnight**: last-sun → next-first-sun
- **Draft**: the in-progress Day, held in memory and replaced when `:day-complete` fires
- **`:day-complete` signal**: fired when PV power drops to zero after having been positive, held for a configurable window (default 30 minutes)
- **`:day-boundary` signal**: midnight — used only for billing resets, not for Day finalisation

## Rationale
- A midnight boundary splits overnight in two — the first half belongs to one calendar day and the second to the next, making overnight performance impossible to compute as a single unit
- Solar window duration (last-sun - first-sun) is a meaningful seasonal KPI; it is only visible if the Day entity spans that window
- Overnight duration is directly related to battery stress — longer winter nights demand more from the battery
- `:day-complete` fires when the sun actually sets, not at an arbitrary clock time; `etoday` from the inverter is already settled at that point
- This means a Day and its following Overnight always pair cleanly into a Cycle without date arithmetic

## Consequences
- (+) Overnight is a coherent unit — one continuous battery discharge from sunset to sunrise
- (+) Solar window duration is a natural Day KPI
- (+) Cycle pairs Day + Overnight without ambiguity
- (+) `etoday` from the inverter is final when `:day-complete` fires — no late-reading corrections needed in the common case
- (-) A Day spans parts of two calendar dates internally (first-sun and last-sun may be on different dates in extreme edge cases — not relevant at this latitude)
- (-) `:day-complete` detection requires a heuristic (30-minute zero-power window) — could misfire during extended cloud cover mid-day; this must be tunable
- (-) Overnight spans two calendar dates — storage and querying uses `from_date` as the primary key, with `to_date` as a join target
