# ADR-006: Actions as Function and Descriptor

## Status
Accepted

## Context
The system needs extensible actions — things that happen when rules fire: send a notification, create an incident, generate a report, update billing state. These actions must be:
- Discoverable by the LLM (so it can reference them when generating rules)
- Executable by the engine (deterministically)
- Extensible (new actions without changing existing code)
- Safe (LLM cannot inject arbitrary executable code)

## Decision
Every action has two parts that ship together in code but serve different consumers:

**The function** — consumed by the engine:
```clojure
(defn send-push-notification! [{:keys [title body channel]}]
  ;; implementation
  )
```

**The descriptor** — consumed by the LLM:
```clojure
{:action/id          :send-push-notification
 :action/label       "Send push notification"
 :action/description "Sends a push notification to the user's device"
 :action/params
 [{:param/key         :title
   :param/type        :string
   :param/required    true
   :param/description "Notification title"}
  {:param/key         :body
   :param/type        :string
   :param/required    true
   :param/description "Body text. Use {:template \"..{entity/field}..\"}  for dynamic values"}
  {:param/key         :channel
   :param/type        :enum
   :param/values      [:push :sms :email]
   :param/required    true}]
 :action/side-effects [:push-notification]}
```

Both are registered at startup. The descriptor is exposed as a catalog (without function references) through MCP resources and injected into LLM system prompts. The function lives only in the code registry.

**Invocation in config store** references action by ID and maps entity data to params:
```clojure
{:action/ref    :send-push-notification
 :action/params {:title   "Battery ran out overnight"
                 :body    [:template "Battery depleted at {overnight/ran-out-at}"]
                 :channel :push}}
```

**Engine resolves at runtime:**
```clojure
(defn execute-action! [invocation entity]
  (let [action-fn (get-in registry [:actions (:action/ref invocation)])
        params    (resolve-params (:action/params invocation) entity)]
    (action-fn params)))
```

Template params (`:template "...{overnight/ran-out-at}..."`) are resolved from entity data before the function is called.

## Rationale
- The LLM reads descriptors and generates invocation descriptors — it never sees or touches function references
- Descriptors are the documentation — no separate docs to maintain, always consistent with the actual function
- New action = new function + new descriptor entry = deploy. No config store change needed to register the action itself
- The catalog exposed to the LLM is derived from the registry at runtime — always current

## Consequences
- (+) LLM discovers available actions from descriptors, generates valid invocations
- (+) Descriptor IS the documentation — no drift between docs and implementation
- (+) Engine execution is deterministic — function lookup from registry, no LLM in hot path
- (+) Malli validates invocation descriptors against param schemas before accepting
- (-) New action types require a deploy (function + descriptor in code)
- (-) Template resolution must handle missing entity fields gracefully — define fallback behaviour
