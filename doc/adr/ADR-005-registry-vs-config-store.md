# ADR-005: Registry (Code) vs Config Store (Data) Separation

## Status
Accepted

## Context
ADR-004 establishes a two-layer architecture. Within the meta layer, there is a critical distinction between things that can change at runtime and things that require a deploy. This boundary must be explicit.

## Decision
**Registry** — code-side, deploy-time, stable:
- Maps IDs → Clojure functions
- Ships with the application
- Changes require a deploy
- Contains: action functions, KPI computation functions, hardware parsers, pipeline handlers

**Config store** — data-side, runtime-editable, live:
- Pure declarative data with no function references
- References registry IDs by keyword
- Changes without deploy
- Persisted in database
- LLM writes here (after validation)
- Contains: rules, dashboards, tariffs, permissions, prompts, KPI benchmarks

**Action descriptors** — code-side, pure data, ships with app:
- Defined alongside functions in code
- Describe what each registered function does and what params it takes
- No function references — just metadata
- The LLM reads these to know what it can reference in rules

```clojure
;; Registry (code — deploy to change)
(def actions
  {:send-push-notification  #'solar.actions/send-push!
   :create-incident         #'solar.actions/create-incident!})

;; Action descriptor (code, pure data — deploy to change)
(def action-descriptors
  {:send-push-notification
   {:action/id          :send-push-notification
    :action/description "Sends a push notification to the user's device"
    :action/params      [{:param/key :title :param/type :string :param/required true}
                         {:param/key :body  :param/type :string :param/required true}]}})

;; Config store (data — change at runtime, no deploy)
{:rule/id        :overnight-battery-failed
 :rule/condition [:eq :overnight/lasted? false]
 :rule/actions   [{:action/ref    :send-push-notification   ;; ID, not fn
                   :action/params {:title "Battery ran out"
                                   :body  [:template "Depleted at {overnight/ran-out-at}"]}}]}
```

## Rationale
- The config store can never contain a Clojure function reference — this prevents the LLM from injecting executable code
- All `:action/ref` values in the config store are validated against the registry at write time — if the function does not exist, the descriptor is rejected
- This makes LLM-authored configuration safe: it can only reference things that a developer has already approved and deployed
- Action descriptors ship with code so they are always consistent with the actual function signatures

## What Changes Without Deploy
| Concern | Layer | Deploy required? |
|---|---|---|
| New action function | Registry | Yes |
| New KPI computation | Registry | Yes |
| New hardware parser | Registry | Yes |
| Malli validation schemas | Code | Yes |
| Action descriptors | Code (pure data) | Yes |
| Alert rules | Config store | No |
| Dashboard layouts | Config store | No |
| Tariff structures | Config store | No |
| Permission sets | Config store | No |
| Prompt templates | Config store | No |
| KPI benchmarks | Config store | No |
| Alert message text | Config store | No |

## Consequences
- (+) LLM can configure system behaviour without introducing executable code
- (+) Config store changes are safe — validated against registry at write time
- (+) Clear boundary: if it references a function, it belongs in code; if it references an ID, it belongs in the config store
- (+) Config store is auditable data — all changes logged with source (user, llm-mcp, llm-chat, system)
- (-) New capabilities (new action types, new KPI formulas) still require a deploy
- (-) The registry and config store must stay consistent — stale config refs break silently if registry changes remove an ID; validation at startup catches this
