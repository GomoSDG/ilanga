# ADR-025: Municipal Bill Reconciliation via Mobele

## Status
Accepted

## Context
ADR-021 calculates the expected electricity bill from the tariff descriptor and BillingCycle accumulated consumption. This calculated bill is only trustworthy if the tariff model is correct and consumption tracking is accurate. Without comparing against the actual billed amount, errors in block thresholds, rate transcription, or missed readings accumulate silently.

The City of Johannesburg customer portal (and most SA municipal portals) provides downloadable monthly statements. Mobele can retrieve these statements automatically.

## Decision

### A Mobele job downloads the monthly statement and POSTs it for reconciliation

A scheduled Mobele job runs once per month, triggered a few days after the billing cycle close (configurable offset, default: 5 days after month-end — allows for municipal statement generation delay). It:
1. Logs into the CoJ customer portal using stored credentials
2. Navigates to the most recent statement
3. Extracts: billing period, total amount due, energy charge line item, fixed charge line items
4. POSTs to `POST /ingest/v1/bill`

### Bill payload

```clojure
{:bill/period-start    #inst "2026-06-01T00:00:00Z"
 :bill/period-end      #inst "2026-06-30T23:59:59Z"
 :bill/total-due       1847.50
 :bill/currency        :zar
 :bill/line-items      [{:item/label "Service charge"  :item/amount 250.00}
                        {:item/label "Energy"           :item/amount 1425.00}
                        {:item/label "Network levy"     :item/amount 172.50}]
 :bill/source          :mobele/coj-portal
 :bill/retrieved-at    #inst "2026-07-05T08:15:00Z"}
```

### Reconciliation is engine-computed, not Mobele-computed

Mobele extracts and delivers the bill. The engine performs the reconciliation:

```clojure
{:reconciliation/period        "2026-06"
 :reconciliation/calculated    1821.75    ;; from ADR-021 BillingCycle
 :reconciliation/actual        1847.50    ;; from bill
 :reconciliation/delta         25.75      ;; actual - calculated
 :reconciliation/delta-pct     1.4        ;; %
 :reconciliation/status        :within-tolerance  ;; or :investigate
 :reconciliation/tolerance-zar 50.00}             ;; configurable
```

`:reconciliation/status :investigate` fires a notification action if the delta exceeds the configured tolerance. The user can then inspect which line item diverges.

### Credentials are stored in Mobele config, not pv-app

Portal login credentials (username + password) are Mobele's concern — they are not sent to pv-app and do not appear in the ingest payload. pv-app receives only the extracted statement data. This keeps authentication credentials outside the pv-app boundary entirely.

### What reconciliation catches

- Wrong block thresholds in the tariff descriptor (calculation assigns kWh to wrong block)
- Rate transcription errors (a rate digit wrong)
- Missed readings that cause accumulated consumption to undercount
- Municipal surcharges or levies not modelled in the tariff descriptor
- VAT treatment differences (VAT-inclusive vs VAT-exclusive rate entry)

### Reconciliation result is a first-class data artifact

Reconciliation results are stored in DuckDB alongside BillingCycles. They are queryable by the LLM in analysis mode (ADR-014) and surfaced in period reports. A history of reconciliation deltas is more useful than a single month's check — a consistently small delta in one direction signals a systematic model error.

## Rationale
- Comparison against the actual bill is the only way to validate the tariff model in production
- Engine-side reconciliation (not Mobele-side) means the comparison uses the same calculated value that would appear in a user-facing report — no separate calculation path
- Tolerance threshold + notification keeps the user out of the loop for close matches and in the loop for real discrepancies
- Credential isolation (Mobele-only) limits the blast radius of a credential leak — pv-app stores no portal passwords
- Storing reconciliation history enables trend detection that a one-off check misses

## Consequences
- (+) Tariff model errors are caught monthly and quantified
- (+) Reconciliation history enables systematic error detection over time
- (+) Portal credentials are isolated to Mobele — not in pv-app config or logs
- (+) Notification only on tolerance breach — no noise for rounding differences
- (-) Portal login automation is fragile — CAPTCHA, session timeouts, or portal redesigns will break the job
- (-) Municipal statements may use different billing period boundaries than the system's BillingCycle — reconciliation must account for partial-cycle misalignment
- (-) The 5-day post-cycle delay is a guess; some municipalities take longer to publish statements
- (-) If the portal adds MFA or changes its login flow, the Mobele job requires manual repair
