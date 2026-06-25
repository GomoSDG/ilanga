# ADR-013: Shared Permission System Across Interfaces

## Status
Accepted

## Context
The system has two LLM interfaces (MCP and internal chat — see ADR-012). Both serve the same home user. The question is whether to differentiate capabilities by interface or by an explicit permission system.

Differentiating by interface (MCP gets technical tools, internal chat gets simplified tools) makes assumptions about how the user wants to interact. The user may prefer to use Claude Desktop for complex configuration or may prefer the in-app chat — this is a UX choice that should not dictate capability.

## Decision
Permissions are the sole differentiator between what different sessions/connections can do. Interface type is irrelevant to capability.

**Permission descriptors** live in the config store:

```clojure
{:permission/id    :default
 :permission/label "Home user"
 :permission/allow
 {:tools     #{:query-days :query-period :create-incident
               :resolve-incident :generate-llm-report}
  :resources #{:solar/site :solar/day :solar/overnight
               :solar/draft :solar/period :solar/incidents}
  :kpis      #{:energy-kwh :final-yield :self-sufficiency-rate
               :overnight-lasted? :co2-avoided-kg :savings-net}
  :register  #{}}}

{:permission/id    :admin
 :permission/allow {:tools :all :resources :all :kpis :all :register :all}}
```

**Sessions carry a permission-id** — the same field on both interface types:

```clojure
;; Internal chat session
{:session/id            "sess-001"
 :session/permission-id :default
 :session/messages      [...]}

;; MCP connection
{:connection/id            "mcp-001"
 :connection/permission-id :default}
```

**One enforcement function** used by both:

```clojure
(defn available-tools [permission-id]
  (let [allowed (get-in @config [:permissions permission-id :permission/allow :tools])]
    (->> (action-catalog)
         (filter #(or (= :all allowed)
                      (contains? allowed (:action/id %)))))))
```

**The LLM never sees tools it cannot call.** The tool list is filtered before being sent — no refusals at runtime, no "you don't have permission" responses. The LLM simply does not know restricted tools exist.

## Granting Elevated Permissions
A homeowner can grant temporary elevated permissions to a technician — effective on whichever interface the technician uses:

```clojure
{:permission/id    :technician-temp
 :permission/allow
 {:tools    #{:query-days :query-period :create-incident
              :resolve-incident :register-rule}
  :resources :all
  :register  #{:rules :incidents}}
 :permission/expires #inst "2026-06-30"}
```

## Tenant Binding (ADR-026)
Permission descriptors carry the tenant they scope, so a session is bound to one tenant's data — the portable isolation guarantee. Within that tenant the session scopes *site* (`site_id`) through its queries; `tenant_id` is the isolation boundary and is never a query field:

```clojure
{:permission/id         :default
 :permission/tenant-id "home"      ;; → ADR-026: scopes all data access to this tenant
 :permission/allow {...}}
```

Device connections resolve `tenant-id` and `site-id` from the device registry (ADR-020). LLM sessions (MCP, internal chat) resolve `tenant-id` from their permission-id and scope `site-id` through their queries. Locally this determines which datasource a session may touch; in cloud it sets the row-level-isolation variable (ADR-008 mechanism; binding per ADR-026). A session scoped to tenant `"home"` can never query another tenant's data — the binding is enforced at `open-store` (the `TenantStore` clients are tenant-scoped), not left to individual queries or domain code.

## Rationale
- Interface-based capability differentiation makes assumptions about user intent that should not be baked into the system
- A single permission system means granting access once works everywhere
- Filtering the tool list before sending to the LLM is cleaner and safer than runtime refusals
- Permission descriptors in the config store means new permission sets can be created without a deploy

## Consequences
- (+) Permissions are portable — a permission-id grants the same access on MCP and internal chat
- (+) No runtime "you can't do that" — the LLM is never shown tools it cannot call
- (+) Temporary elevated access (e.g. for a technician visit) is a config store entry with an expiry
- (+) Permission descriptors are auditable — changes logged in `config_history`
- (✓) Session permission-id acquisition is decided (was an open item): device connections resolve it from the device registry (ADR-020); LLM sessions (MCP, internal chat) resolve it at session establishment (ADR-020); both are bound to a single tenant via `:permission/tenant-id` (ADR-026)
- (-) Permission expiry must be enforced at connection/session time — the engine must check `:permission/expires` on every request
- (-) `:all` as a permission value requires careful handling in enforcement functions — must not be mistaken for a set containing the keyword `:all`
