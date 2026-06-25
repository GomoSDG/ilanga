# ADR-024: Tariff Rate Update Automation via Mobele

## Status
Accepted

## Context
ADR-021 established that tariff descriptors live in the config store and rate changes are config edits — no deploy required. The gap is delivery: CoJ and other municipalities publish revised tariff tables annually (typically July), and without automation the user must manually read the gazette or municipal website, extract the block thresholds and rates, and write the new descriptor.

Manual transcription is error-prone and easy to miss. A wrong rate means savings calculations are silently wrong for an entire billing year.

## Decision

### A Mobele job monitors the tariff source and submits new descriptors

A scheduled Mobele job runs on a configurable interval (default: weekly, increasing to daily in June when the CoJ financial year starts). It:
1. Fetches the CoJ tariff page (or configured tariff source URL) for the active tariff class
2. Extracts block thresholds and rates using a Mobele extractor
3. Computes a content hash of the extracted rates
4. Compares against the hash of the current active tariff descriptor
5. If changed: validates the extracted descriptor shape, then POSTs to `POST /ingest/v1/tariff`
6. If unchanged: exits silently

### Tariff payload

```clojure
{:tariff/id        :coj-residential-2027        ;; year-stamped, never overwrites prior
 :tariff/label     "City of Johannesburg Residential 2026/27"
 :tariff/currency  :zar
 :tariff/effective #inst "2026-07-01T00:00:00Z"
 :tariff/source    "https://www.joburg.org.za/..."
 :tariff/source-hash "sha256:abc123..."
 :tariff/charges   [...]}                       ;; full ADR-021 descriptor
```

The ingest endpoint writes this as a new descriptor. It does not overwrite the previous year's descriptor — historical BillingCycles can always be recalculated at the rates that were in effect.

### Activation is manual

Writing the new descriptor does not automatically activate it. The system notifies the user (via a registered notification action) that a new tariff descriptor is available. The user reviews the extracted values against the official gazette and activates by updating `:site/tariff-id` in the config store. This review step is intentional — transcription errors in automated extraction should not silently update billing calculations without human sign-off.

Activating a tariff — writing `:site/tariff-id` — is outside the LLM `:register` scope (ADR-005/014): it is a human-in-the-loop config change, not an LLM-authored descriptor. The LLM may author the *tariff descriptor* itself (via `:register`); it may not flip which tariff is *active*. This keeps financial-critical state under explicit human control.

### Pre-submission validation

Before POSTing, the Mobele job validates the extracted descriptor shape:
- All blocks have numeric thresholds and rates
- Rates are in a plausible range (configurable: default 0.50–20.00 ZAR/kWh — flags if extraction pulled the wrong numbers)
- Block thresholds are monotonically increasing
- Fixed charges are positive

If validation fails, the job exits with an error and does not POST — a corrupt extraction is not submitted.

### Tariff source URL is config, not code

`:tariff/source-url` lives in the config store. Different municipalities or tariff classes are handled by writing a different source URL — no Mobele job changes required.

## Rationale
- Content hash comparison means the job only submits when rates actually change — no redundant descriptor writes
- Year-stamped IDs and no-overwrite policy preserve historical tariff data for BillingCycle recalculation (ADR-021)
- Manual activation keeps a human in the loop for financial data — automated extraction + human review is the correct split of labour
- Plausibility range check catches the most common extraction failure mode (wrong element selected, rate in cents not rand)
- Source URL in config means the job is reusable across municipalities without modification

## Consequences
- (+) Rate revisions are detected automatically — no missed annual update
- (+) Historical descriptors are preserved — past billing can always be recalculated correctly
- (+) Human review before activation prevents a bad extraction silently corrupting savings calculations
- (+) Pre-submission validation catches obvious extraction failures before they reach the config store
- (-) Tariff page structure changes (redesign, URL change) will break extraction until the Mobele job is updated
- (-) Weekly polling means a July 1 tariff change may not be detected until the next scheduled run
- (-) Activation is manual — if the user forgets to activate after being notified, calculations use stale rates
- (-) Rate plausibility range must be maintained — if CoJ rates ever exceed the configured ceiling, the guard would incorrectly reject a valid extraction
