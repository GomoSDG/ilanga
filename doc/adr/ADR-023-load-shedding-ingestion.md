# ADR-023: Load Shedding Schedule Ingestion via Mobele

## Status
Accepted

## Context
Load shedding is a first-class domain concept in this system — it affects energy production, overnight battery draw, and KPI interpretation. Without knowing when load shedding occurred, a day with low generation looks like a panel problem; an overnight that ran out of battery looks like undersized storage. Manually creating Incident entities for every load shedding window is not sustainable.

Eskom and City Power (CoJ) publish load shedding schedules online. The schedule maps stage + area to time blocks. The user's area is fixed and known.

## Decision

### A Mobele job scrapes the schedule and POSTs Incidents via ADR-022

A scheduled Mobele job runs periodically (configurable, default: every 2 hours). It:
1. Fetches the current load shedding stage from the Eskom/EskomSePush-compatible source
2. Fetches the area schedule for the configured area code
3. Derives the upcoming time windows for the current and next day
4. POSTs each window as an Incident to `POST /ingest/v1/incidents`

### Incident shape for load shedding

```clojure
{:incident/type       :load-shedding
 :incident/stage      2                          ;; Eskom stage 1–8
 :incident/area-code  "JHB-4-NORTHCLIFF"
 :incident/starts-at  #inst "2026-06-23T16:00:00Z"
 :incident/ends-at    #inst "2026-06-23T18:30:00Z"
 :incident/source     :mobele/eskom-schedule
 :incident/external-id "eskom-jhb4-20260623-1600"} ;; idempotency key
```

`:incident/external-id` is a deterministic key derived from area + date + slot. The ingest endpoint upserts on this key — re-running the job does not create duplicate Incidents.

### Area code is config, not code

The user's load shedding area code lives in the config store under `:site/load-shedding-area`. The Mobele job reads this from the pv-app config endpoint at job start. If the area code is not configured, the job skips with a warning — no hardcoded default.

### Window calculation

Eskom stages map to a published block schedule. The Mobele job converts stage + block table + area code into UTC time windows, accounting for the system's configured local timezone (UTC+2; recorded in the user-timezone project note — DB stores UTC, reported in local time). Future windows within a 48-hour horizon are submitted; past windows within the same run are skipped.

### Incident lifecycle

Load shedding Incidents are auto-closed by the engine on the `:tick` signal (ADR-007) when `now > :incident/ends-at`. If the stage changes mid-window (escalation or reduction), the next job run submits updated windows — the old Incident for that slot is superseded by the upsert.

### Failure handling

If the Mobele job cannot reach the schedule source or the pv-app ingest API, it logs the failure and exits. The last successfully ingested schedule remains active. Incidents already written are not retracted. The system continues with stale schedule data until the next successful run.

## Rationale
- Idempotent upsert on external-id means the job can run frequently without accumulating duplicates
- Area code in config store means it can be changed without touching Mobele job definitions
- Auto-close by engine (not by Mobele) keeps Incident lifecycle logic inside the system boundary
- 2-hour polling interval balances freshness against hitting schedule sources repeatedly

## Consequences
- (+) Load shedding Incidents are created automatically — no manual entry
- (+) KPI interpretation (PR, overnight battery duration) is correctly contextualised against load shedding windows
- (+) Stage level is recorded — analysis can correlate system behaviour against stage severity
- (-) Schedule sources (Eskom website, EskomSePush) are not under our control — scraping may break on site changes
- (-) 2-hour polling means up to 2 hours of lag between a surprise stage change and the system knowing about it
- (-) Area code must be correctly configured; a wrong area code produces silently wrong windows
- (-) No real-time push — if Eskom announces load shedding with 30 minutes notice, the system may not have the Incident before the window starts
