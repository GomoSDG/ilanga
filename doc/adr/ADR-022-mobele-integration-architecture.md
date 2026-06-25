# ADR-022: Mobele Integration Architecture

## Status
Accepted

## Context
Three external data needs require web scraping: load shedding schedules (ADR-023), tariff rate updates (ADR-024), and municipal bill reconciliation (ADR-025). All require browser automation against South African municipal and utility websites that do not offer stable APIs.

Mobele (`~/merero/mobele-2`) is an existing browser automation framework with a visual job editor, registry-based extension in Clojure, and a custom sink system. It runs as a separate process (its own Node.js runner), deployed independently.

The question is how Mobele delivers data to this system without coupling to its internals.

## Decision

### pv-app exposes a lightweight HTTP ingest API

Mobele writes to pv-app via HTTP — it does not touch DuckDB, SQLite, or any internal store directly. The ingest API is the only interface between the two systems.

```
POST /ingest/v1/incidents      → creates or updates an Incident (ADR-002)
POST /ingest/v1/tariff         → writes a new tariff descriptor to the config store (ADR-021)
POST /ingest/v1/bill           → records a municipal bill statement for reconciliation
```

All endpoints share the same auth, validation, and error contract. New integrations add a new endpoint; nothing else changes.

Each ingest endpoint is a fourth, LLM-independent interface to the system: it dispatches through the **action registry** (ADR-005/006). `:create-incident`, `:update-tariff`, and `:record-bill` are registered actions — the same ones the LLM reaches via tools (ADR-012) and the engine reaches via the pipeline (ADR-007). The ingest API is thus not a parallel write path; it is another caller of the same registered actions, gated by the same permission check (the API key's `permission-id`, ADR-013).

### Auth: API key mapped to permission-id

Mobele presents an API key in the `Authorization` header. The key is stored in the config store and mapped to a permission-id (ADR-013), resolved the same way as MCP and internal chat sessions (ADR-020):

```clojure
;; Config store
{:api-key/token       "mk_..."
 :api-key/label       "Mobele automation"
 :api-key/permission-id :automation}

;; Permission descriptor
{:permission/id    :automation
 :permission/allow
 {:tools     #{:create-incident :update-tariff :record-bill}
  :resources #{:solar/incidents :solar/config :solar/billing}
  :register  #{}}}
```

Unknown keys are rejected with 401. The `:automation` permission is narrower than `:default` — it can write incidents and tariff descriptors but cannot query energy data or access LLM tools.

### Mobele custom sink

Each integration registers a Mobele sink that POSTs the extracted data to the ingest API:

```clojure
(shared/register-sink! :pv-app-ingest
  (fn [result context]
    (http/post (str (:pv-app/base-url context) (:pv-app/path context))
               {:headers {"Authorization" (str "Bearer " (:pv-app/api-key context))}
                :body    (json/encode (:data result))})))
```

The sink is reused across all three integrations — the endpoint path differs, the mechanism is identical.

### Validation at the ingest boundary

Each endpoint validates the incoming payload with Malli before writing. An invalid payload returns 422 with structured errors — Mobele logs these and the job is marked failed. No partial writes.

### Mobele and pv-app are independently deployed

Mobele runs on its own schedule (cron-style jobs in the Mobele job editor). pv-app does not depend on Mobele being up. If Mobele is down, no external data is ingested; the system continues with its last known state. If pv-app is down, Mobele jobs fail and retry on their next scheduled run.

## Rationale
- HTTP boundary keeps the two systems decoupled — Mobele cannot corrupt pv-app's internal store directly
- Reusing the permission system (ADR-013) means automation access is auditable and revocable the same way human access is
- Custom sink encapsulates the HTTP call — individual jobs declare what data they extract, not how it's delivered
- Validation at the ingest boundary means Mobele data is held to the same schema standards as engine-generated data

## Consequences
- (+) Mobele and pv-app are independently deployable and independently restartable
- (+) Automation access is governed by the same permission system as LLM and human access
- (+) New integrations add an endpoint and a Mobele job; no changes to the core system
- (-) pv-app must run an HTTP server — adds a listener beyond the TCP inverter port and the MCP/chat interfaces
- (-) Network call from Mobele to pv-app can fail; Mobele job retry is the recovery mechanism
- (-) API key management is manual — the key must be provisioned in both Mobele config and the pv-app config store
