# ADR-014: LLM as Registrar and Analyst, Not Executor

## Status
Accepted

## Context
The system uses LLMs (via MCP and internal chat) to extend its behaviour and to answer questions. The risk is that LLM non-determinism contaminates runtime behaviour — if the LLM is in the hot path of rule evaluation, alert generation, or KPI computation, results become unpredictable and unauditable.

At the same time, the LLM's reasoning capability is genuinely valuable for: authoring new rules in natural language, interpreting KPI results in context, generating reports that account for incidents and seasonal factors, and answering ad-hoc questions.

## Decision
The LLM operates in exactly two modes. It is never in the hot path of deterministic evaluation.

**Registration mode** — the LLM authors config:
1. Reads action catalog (what tools are available)
2. Reads registry schemas (what valid descriptors look like)
3. Reads existing config (current rules, dashboards, tariffs)
4. Generates a descriptor in response to a natural language request
5. Submits it via a tool call (`register_rule`, `register_tariff`, etc.)
6. The system validates with Malli before accepting
7. On success: written to config store, logged with `changed_by: llm-mcp` or `llm-chat`
8. On failure: Malli errors returned to LLM for correction

**Analysis mode** — the LLM interprets data:
1. Receives a context package: recent Days, Overnights, Cycles, Periods, active Incidents, System config
2. Receives pre-computed KPI values with `:kpi/assessment` already determined by the engine
3. Produces natural language reports, explanations, and recommendations
4. May call query tools to fetch additional data
5. Does not write to the data layer or config store during analysis

The LLM never:
- Evaluates alert rule conditions (the engine does this deterministically)
- Computes KPI values (the engine does this)
- Executes actions directly (the engine does this when rules fire)
- Writes raw computed numbers back to the data layer

## The Context Package for Analysis
```clojure
{:site             site-config
 :today            draft              ;; in-progress day
 :recent-days      (last-n-days 7)
 :active-incidents (active-incidents)
 :current-period   (current-month-period)
 :season           :za-winter
 :registered-rules (rule-catalog)    ;; LLM can reason about its own rules
 :question         "Why was yesterday bad?"}
```

`:kpi/assessment` is always pre-computed — the LLM reads `:typical` / `:good` / `:below-typical` and generates appropriate language. It never compares raw numbers against benchmarks itself.

## LLM-Triggered Analysis Actions
An action of type `:generate-llm-report` can be registered and triggered by a rule. This is the only way the LLM enters the analysis path from a rule firing — as a registered action, not as part of rule evaluation itself:

```clojure
{:action/ref    :generate-llm-report
 :action/params {:prompt-id :weekly-summary
                 :audience  :home-user
                 :context   [:period/week :incidents/active]}}
```

The engine executes this action deterministically (calls the function). The function calls the LLM API. The LLM produces a report. The report is stored or sent — not used to trigger further rules.

## Rationale
- Non-determinism is contained to registration time (authoring a descriptor) and analysis time (producing a report) — both are moments where human review is possible
- Every LLM-authored config change is a data artifact in the config store — inspectable, editable, rollback-able
- The engine's determinism is preserved — rules always fire the same way, KPIs always compute the same way
- Validation before acceptance means a hallucinated field name in a descriptor fails fast with a clear error, not silently at rule-fire time

## Consequences
- (+) Runtime behaviour is fully deterministic and auditable — independent of LLM behaviour
- (+) LLM registration outputs are reviewable data artifacts before they affect anything
- (+) Analysis is contextually rich — the LLM receives pre-assessed KPIs and can explain them without recomputing
- (+) LLM errors at registration time produce Malli validation errors — clear, correctable, do not affect the running system
- (-) LLMs cannot self-modify the engine or registry — new capabilities still require a developer and a deploy
- (-) Context package construction must be careful about size — too much data and the context window fills; too little and the LLM lacks the context to reason well
- (-) LLM-generated reports are not stored in the data layer by default — a `store-report` action must be explicitly registered if persistence is needed
