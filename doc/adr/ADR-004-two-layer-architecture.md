# ADR-004: Two-Layer Architecture — Data Entities and Declarative Config

## Status
Accepted

## Context
The system needs to be extensible without redeployment. New tariff structures appear when municipalities change rates. New alert rules are needed as users learn what matters to them. New dashboard layouts are wanted as the product evolves. New KPI definitions are added as the domain understanding deepens. Hardcoding this behaviour in application logic requires a deploy for every change.

At the same time, correctness and auditability require that the runtime behaviour be deterministic — not subject to LLM non-determinism.

## Decision
Two distinct layers:

**Data layer** — immutable facts about what happened:
```
Reading, Draft, Day, Overnight, Cycle, Period
System, Incident, Tariff, BillingCycle
```

**Meta layer** — declarative descriptors that tell the engine how to behave:
```
Registry     — code-side: ID → function mappings (deploy-time)
Config store — data-side: pure descriptors referencing registry IDs (runtime)
```

The engine is thin. It reads the meta layer and applies it to the data layer. All business logic is expressed as data.

## Rationale
- **Behavior as data** means new rules, new KPIs, new alert conditions, and new tariff structures can be added by writing a descriptor — not by writing code
- **LLM as author**: the LLM can generate valid descriptors and write them to the config store. It cannot change the engine or the registry — those require a deploy
- **Deterministic runtime**: the engine evaluates descriptors deterministically. The LLM's non-determinism is contained to the registration step, where its output is a data artifact that can be inspected and rolled back before it affects anything
- **Thin engine** means there is one place where behaviour is executed — easier to test, easier to audit, easier to reason about

## Alternatives Considered
- **Hardcoded business logic**: fast to start, impossible to extend without deploy, no LLM integration possible
- **Rule engine library (Drools, Clara Rules)**: powerful but introduces a heavy dependency and its own DSL; the Clojure data model achieves the same with `defmulti` and maps
- **Everything as code, hot-reloaded**: possible with Clojure's dynamic nature but breaks the data/code boundary and makes LLM-authored changes dangerous

## Consequences
- (+) Tariff structures, alert rules, dashboards, and prompt templates change without deploy
- (+) LLM can extend the system's behaviour by writing to the config store
- (+) All runtime behaviour is traceable to a descriptor in the config store
- (+) The meta layer is readable by both the engine and the LLM — one source of truth
- (-) Indirection: debugging requires understanding both the descriptor and the engine
- (-) The config store must be validated before descriptors reach the engine — Malli schemas are required
- (-) Some behaviour cannot be expressed declaratively and requires a new function (new action, new KPI formula) — this still requires a deploy
