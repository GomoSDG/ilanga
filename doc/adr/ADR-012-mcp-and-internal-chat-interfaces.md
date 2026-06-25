# ADR-012: MCP Server and Internal Chat API as Dual LLM Interfaces

## Status
Accepted

## Context
The system needs LLM integration for two purposes: allowing the LLM to register new config (rules, dashboards, tariffs) and allowing the LLM to analyse data and produce reports. Users want to access these capabilities through Claude Desktop (via MCP) or through an in-app chat interface. The user decides which interface to use — both serve the same home user.

## Decision
Two LLM interfaces sharing the same registry, permissions, engine, and data layer:

**MCP Server** — for users connecting via Claude Desktop, Claude Code, or any MCP-compatible client:
- Exposes **tools** derived from action registry (permission-filtered)
- Exposes **resources** from the data layer and registry
- Exposes **prompts** from the prompt registry
- Authentication via connection-level permission-id

**Internal Chat API** — for users of the in-app chat interface:
- Calls Claude via Anthropic SDK
- Sends permission-filtered tool definitions as Anthropic tool format
- Builds context package from data layer
- Manages conversation session history

Both interfaces:
- Read from the same registry and config store
- Use the same `available-tools` and `available-resources` functions
- Route action execution through the same engine
- Apply the same permission system

The tool schema derivation is mechanical from the action descriptor — one source of truth, two wire formats:

```clojure
(defn action->mcp-tool [descriptor]
  {:name        (name (:action/id descriptor))
   :description (:action/description descriptor)
   :inputSchema (params->json-schema (:action/params descriptor))})

(defn action->anthropic-tool [descriptor]
  {:name         (name (:action/id descriptor))
   :description  (:action/description descriptor)
   :input_schema (params->json-schema (:action/params descriptor))})
```

MCP resources expose:
```
solar://system                  system config and specs
solar://registry/actions        action catalog for LLM discovery
solar://registry/rules          registered rules
solar://registry/schemas/{type} Malli schema for that descriptor type
solar://draft/today             today in progress
solar://day/{date}              specific day summary
solar://overnight/{date}        specific overnight summary
solar://period/{yyyy-mm}        monthly period
solar://incidents/active        active incidents
```

## Rationale
- The user choosing MCP vs internal chat is a UX preference, not a capability boundary
- One registry, one engine, one permission system — no duplicated business logic
- Schema derivation from action descriptors means the LLM always sees a consistent tool definition regardless of interface
- MCP resources make the data layer queryable by any MCP-compatible client without a custom integration

## Consequences
- (+) Adding a third interface (e.g. Slack bot) is contained — implement wire format, share everything else
- (+) No duplication: business logic, permissions, and action execution live in one place
- (+) Action descriptors are the single source of truth for tool definitions on both interfaces
- (-) Two JDBC wire formats for tools must be kept consistent — `params->json-schema` is the single conversion function that must handle all param types correctly
- (-) MCP requires a running server endpoint; internal chat requires Anthropic API access — both have uptime dependencies
- (-) Context package construction must be efficient — the same data layer serves both interfaces and is read on every chat turn
