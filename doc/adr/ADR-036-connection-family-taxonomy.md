# ADR-036: Inverter Connection Family Taxonomy

## Status
Accepted

## Context
The ingestion design (ADR-018, TDD-01) grew around the Sacolar/CubeWiFi path — a device that connects outbound, announces its serial, and pushes framed telemetry. That shape is **not universal**. Inverter and datalogger connections differ in two load-bearing ways: *who initiates the connection* and *where identity comes from*. Treating the push-stream shape as the base of the connection abstraction couples that abstraction to one family and forces a rewrite when a second family lands.

This ADR records the connection families observed (evidence-based, from public protocol docs) so the push-stream shape is **one session kind among siblings, not the universal base**. It complements ADR-018 (which owns the push-stream ingestion design) and ADR-037 (which owns the push-stream descriptor vocabulary).

## Decision
Five connection families, distinguished by *(who initiates, where identity comes from)*:

1. **Push-stream** — the device initiates an outbound TCP connection, announces identity (a registration/announce frame carrying the serial), pushes framed telemetry on an interval, and expects per-frame acks. The server is passive (accepts) and emulates the minimal acks/keepalive the device needs. *Examples: Sacolar/Growatt CubeWiFi, Sofar Solarman-v5 (port 10000), GoodWe POSTGW (port 20001).* Identity = announced on connect.
2. **Modbus-TCP poll** — the server initiates to the device and reads telemetry by polling holding/input registers on a schedule. No announce; identity is configured on the poll job (which device, which unit-id). *Examples: SolarEdge, Fronius Modbus, Huawei, Sungrow, Victron, SAJ, SMA-modern.* Identity = a priori from config.
3. **HTTP poll** — the server polls a REST/JSON local API on a schedule. Identity configured. *Examples: Fronius Solar API, Enphase Envoy.* Identity = a priori.
4. **Bus / broadcast** — the device emits on a shared bus (CAN); the server listens. Identity = the source address in each frame. *Example: Victron VE.Can.*
5. **MQTT subscribe** — the device publishes to a broker; the server subscribes. Identity = the topic / client-id. *Example: Victron via FlashMQ.*

### The rule
The **generic connection/session owns only the invariant**: identity is bound before any telemetry is routed or written (ADR-020/026). *How* identity is established is family-specific and owned by the **family session kind**. The push-stream session kind owns announce-wait and the handshake; poll/MQTT/bus/HTTP own their respective acquisition. **All families converge at the canonical `Reading`** (the ADR-035 port) — from that seam onward nothing knows which family produced it: validate → `write!` → `:inserted` → reading-channel → engine.

This mirrors the split already settled for the push-stream family itself: framing *bytes* are descriptor data (`:framing`, ADR-034), framing *logic* is the generic framer (code); field *offsets* are descriptor data (`:fields`/`:compute :inputs`, ADR-033), field-compute *algorithms* are codec fns (code). Here: the identity invariant is generic (code, in the connection); the acquisition mechanism is family-specific (code + descriptor, in the family session kind).

### Scope now: push-stream only
The push-stream session kind is the one being built (the Sacolar/CubeWiFi path); its handler is data-driven off the descriptor (ADR-037). Poll, MQTT, bus, and HTTP are **deferred sibling session kinds** — erected when a real device of that family lands, plugging into the same connection→handler fn seam without rewriting the connection.

## Consequences
- (+) The push-stream shape does not become the universal base; a second family is an additive session kind, not a rewrite.
- (+) The connection abstraction stays thin — the identity-before-telemetry invariant; family specifics live in family handlers.
- (+) All families share the Reading→ingest→port→channel path; divergence is confined to the acquisition front-end.
- (−) A family-dispatch mechanism (a `Session` protocol / `:connection/family` dispatch) is **not built now** — deferred until a 2nd family lands. A one-method dispatch would be speculative (same "don't decide before evidence" principle as ADR-032); the connection→handler call stays a single fn seam so a 2nd family plugs in without connection changes.
- (−) The taxonomy is evidence-based from public protocol docs, not from operating each family — details may refine when a real 2nd-family device lands.

## Open / deferred
- Poll / MQTT / bus / HTTP session kinds.
- The family-dispatch mechanism (erected at the 2nd family).
- Per-family framing the `:framing` vocabulary cannot express (the ADR-034 `:framer-fn` escape-hatch).

## References
- ADR-018 — ingestion TCP server & hardware mapping (push-stream design)
- ADR-020 — connection auth & device registration (the identity-before-telemetry invariant)
- ADR-034 — framing descriptor vocabulary (the data-driven-pattern precedent)
- ADR-037 — push-stream control-routing descriptor vocabulary (the sibling this ADR enables)
- ADR-035 — persistence port (the Reading convergence seam)