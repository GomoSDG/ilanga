# 06 — Tariff, Billing & External Integrations

**Status:** Stub — skeleton only; implementation detail pending.

## Purpose & scope
The financial and external-data layer: tariff modelling + counterfactual savings, billing-cycle accumulation, municipal bill reconciliation, and the Mobele HTTP ingest boundary that feeds load-shedding schedules, tariff-rate updates, and bills into the system. The ingest API is a fourth LLM-independent interface dispatching through the action registry. **Excludes** the permission/auth of the API key (05) and the reconciliation entity's storage (02).

## Governing ADRs
- ADR-021 Tariff modelling — Accepted
- ADR-022 Mobele integration architecture — Accepted
- ADR-023 Load shedding ingestion — Accepted
- ADR-024 Tariff rate update automation — Accepted
- ADR-025 Municipal bill reconciliation — Accepted

## Interfaces
TODO:
- Tariff calculator contract: `(calc-charge charge-descriptor billing-data) → charge-result` (pure, no global state); dispatch on `:charge/type`; total = reduce over charges.
- BillingCycle state: `:billing/consumed-kwh` accumulated incrementally on `:day-complete`; resets on month boundary.
- HTTP ingest API: `POST /ingest/v1/{incidents|tariff|bill}` — shared auth (API key → permission-id), Malli validation, 422 on bad payload, no partial writes; endpoints dispatch through the action registry (`:create-incident`, `:update-tariff`, `:record-bill`).
- Mobele custom sink: one reusable sink POSTs to the ingest API; jobs declare *what* to extract, not *how* to deliver.

## Data structures / schemas
TODO:
- Tariff descriptor (`:tariff/id` year-stamped, `:tariff/charges` with `:charge/type` components — `:fixed-monthly`, `:inclining-block`, `:per-kwh`, `:time-of-use`).
- Charge calculators registry (`:fixed-monthly`, `:inclining-block`, `:time-of-use`, `:per-kwh`).
- Charge result with `:charge/detail` block breakdown (auditable).
- BillingCycle and the inclining-block dependency on accumulated consumption.
- Incident shape for load shedding (`:incident/type :load-shedding`, `:incident/stage`, `:incident/external-id` idempotency key).
- Reconciliation artifact (`:reconciliation/calculated`, `:reconciliation/actual`, `:reconciliation/delta`, `:reconciliation/status`, `:reconciliation/tolerance-zar`).

## Sequences / flows
TODO:
- Savings = counterfactual, same calculator: `calc-savings` runs the tariff on `consumed-kwh-without-solar` vs actual; no separate savings formula. **No-export assumption**: `consumed-without-solar = consumed + solar-self-consumed`; under no-export, `= consumed + solar-produced`; export credit (if any) is a separate line item, never folded in.
- Day close → day's grid consumption added to BillingCycle → calculator receives cycle accumulated total.
- Load shedding: Mobele job (every ~2h) → POST Incidents upserted on `:incident/external-id`; engine auto-closes on `:tick` when `now > :incident/ends-at` (ADR-007/023); timezone from the user-timezone note (DB stores UTC, reported local).
- Tariff update: Mobele job (weekly→daily in June) → content-hash compare → if changed, validate shape + plausibility range → POST new year-stamped descriptor (no overwrite) → **manual activation** by human writing `:site/tariff-id` (outside LLM `:register` scope).
- Bill reconciliation: monthly Mobele job (~5 days post cycle close) → POST bill → engine computes reconciliation → `:investigate` fires a notification if delta exceeds tolerance; credentials isolated to Mobele.

## Invariants & error modes
TODO:
- Savings and billing use one model — they cannot diverge (counterfactual reuses the same calculator).
- Historical descriptors are never overwritten — past BillingCycles recalculate at the rates in effect.
- Tariff *activation* is human-only; LLM may author the descriptor but not flip the active tariff.
- Idempotent ingest on `:incident/external-id` — re-running a Mobele job never duplicates Incidents.
- Portal-login automation is fragile (CAPTCHA, MFA, redesigns) — jobs fail and retry; last-known state persists; credentials never enter pv-app.
- Inclining-block correctness depends on BillingCycle accumulation being complete — missed readings mis-assign blocks.

## Open / deferred
- Export/feed-in tariff credit line item (currently no-export; deferred until a tariff that pays for export).
- Billing-cycle vs municipal-statement boundary misalignment (partial-cycle handling in reconciliation).
- Plausibility-range ceiling maintenance if rates exceed the configured guard.