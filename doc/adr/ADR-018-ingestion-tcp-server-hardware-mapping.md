# ADR-018: Ingestion — TCP Server, Growatt Protocol, Hardware Mapping, Canonical Reading

## Status
Accepted

## Context
The Growatt CubeWiFi inverter initiates an outbound TCP connection to a server and pushes binary DATA packets at a regular interval. The system must receive these packets, decode them into canonical Reading entities, persist them to DuckDB, and emit a `:new-reading` signal to the pipeline (ADR-007).

The system is designed to scale to multiple tenants, multiple sites, and multiple inverter connections (ADR-026: tenant = isolation, site = location, device = inverter). Starting small (few devices) is a proof-of-concept phase, not a design constraint — "starting small ≠ designing small."

Previous Python scripts (proxy, decoder) were prototypes for understanding the protocol. The Clojure application owns the full ingestion pipeline. There is no Python dependency.

## Decision

### TCP Server: Aleph (Netty-based)

Aleph handles the byte-transport layer. Each inverter connection becomes a Manifold stream — a first-class, addressable, stateful object with its own backpressure. This is the correct model for a multi-device design intent.

What Aleph buys beyond "async TCP":
- **Per-connection lifecycle and state**: each device is a Manifold stream. Reconnect, idle/timeout, and per-device hardware mapping dispatch are connection-level concerns handled uniformly, not hardcoded in a per-thread loop
- **Non-blocking model**: ingestion never ties up threads that the pipeline, DuckDB writes, or LLM interfaces also want
- **Connection as addressable object**: permission-id association (ADR-013), hardware mapping dispatch, and connection auth all have a clean home on the stream — not scattered across thread-local state
- **Backpressure**: if DuckDB stalls or the pipeline is slow, Manifold stream backpressure propagates naturally to the TCP layer

Raw Java sockets with a thread-per-connection model would have been adequate for one device but would require rewriting exactly the byte-transport layer — precisely where Aleph lives — at the point of scaling. The migration cost is concentrated where Aleph already solves the problem. Paying a small complexity cost now avoids paying a large migration cost later.

The framing, CRC validation, and payload decoding logic is transport-agnostic — identical whether the transport is raw sockets or Aleph. The transport choice affects only the byte-delivery mechanism, not the protocol handling.

### Handoff: core.async channel

After decoding, the canonical Reading is placed on a core.async channel. The pipeline (ADR-007) consumes from this channel and emits the `:new-reading` signal. This decouples ingestion from pipeline processing and gives the system a natural buffer.

### Growatt Cloud: Terminate with minimal server emulation; forwarding is a config toggle defaulting off

The Clojure server terminates the inverter connection. It does not forward packets to Growatt's cloud by default.

The CubeWiFi expects server-side acknowledgements and keepalives to maintain its connection. The ingestion layer emulates the minimal server responses the device requires — enough to keep it streaming, nothing more. This is simpler and more stable than full MITM forwarding because the contract depends on the device's outbound packet format (stable, under our control) rather than Growatt's server API (can change, can enforce TLS pinning, can add auth, can break silently on firmware update).

Forwarding to Growatt's cloud is available as a config-store toggle (ADR-004/005), defaulting off. Enabling it is a runtime decision requiring no deploy. Rationale for defaulting off: forwarding sends household energy data to a third party indefinitely and makes Growatt's cloud an uptime dependency of the system — contrary to the self-contained, data-sovereign character of the architecture (ADR-012).

### Hardware Mapping: ADR-005 registry pattern, developer-authored only

Hardware mapping is a deploy-time registry descriptor following the ADR-005 pattern — not the ADR-006 function+descriptor pattern (which is specific to actions).

The descriptor covers **field extraction only**: byte offsets, value types, and scale factors. The **protocol layer** — framing, XOR obfuscation, and CRC16 — is per-protocol code in the Growatt handler, not expressed in the descriptor. Full protocol detail is in [`doc/protocol/growatt-cubewifi-data-payload.md`](../protocol/growatt-cubewifi-data-payload.md). A new inverter model using the same Growatt protocol = new descriptor, no new code. A new protocol = new code.

The generic decoder reads offsets, types, and scale factors from the descriptor and extracts field values. Note: there is no single `pv_total` field in the packet — total PV power is computed as `pv1 + pv2`.

```clojure
;; Code registry (deploy-time)
(def hardware
  {:growatt-cubewifi #'solar.engine.ingest.growatt/decode-reading})

;; Hardware descriptor (code-side, pure data, deploy-time)
;; Offsets and types from doc/protocol/growatt-cubewifi-data-payload.md
{:hardware/id       "growatt-cubewifi"
 :hardware/protocol :growatt-data-v1
 :hardware/mappings
 {:reading/pv1-power-w      [:offset 79  :type :uint16 :scale 0.1]
  :reading/pv2-power-w      [:offset 83  :type :uint16 :scale 0.1]
  :reading/load-power-w     [:offset 91  :type :uint16 :scale 0.1]
  :reading/grid-power-w     [:offset 145 :type :uint16 :scale 0.1]   ;; unsigned: grid import only, never negative (no export, ADR-021)
  :reading/battery-power-w  [:offset 231 :type :int16  :scale 0.1]   ;; magnitude only — sign resolved by the protocol handler, see below
  :reading/energy-today-kwh [:offset 174 :type :uint8  :scale 0.1]   ;; single byte; max 25.5 kWh — pending re-verification on high-yield summer capture (see protocol doc)
  :reading/energy-total-kwh [:offset 175 :type :uint32 :scale 0.1]
  :reading/temp-c           [:offset 121 :type :uint16 :scale 0.1]}}
;; Computed in the Growatt protocol handler (NOT single-offset mappings — see protocol doc "Battery power decode"):
;;   :reading/battery-current-a — signed net current = discharge_current(243) − charge_current(241)
;;   :reading/battery-power-w sign — taken from the direction-specific current registers, not bit 15 of 231
;; Dropped: :reading/battery-soc-pct at offset 107 — disproven; offset 107/108 is broken BMS noise, not a state-of-charge.
;;   battery-voltage (offset 105) is the only usable charge proxy. (See register-map note, 2026-06-24.)
```

New inverter model (same protocol) = new descriptor = deploy. No new parsing logic.

**Hardware mapping descriptors are developer-authored only.** Byte offsets, type widths, and scale factors require protocol-level knowledge and binary verification — not appropriate for LLM authorship. There is no LLM-facing catalog entry for hardware descriptors. This is an explicit exception to the general pattern where the LLM discovers available registry entries via catalog.

### Connection → Hardware-id, Tenant & Site Resolution

When a device connects, its serial number is extracted from the announce packet. The serial is looked up in a config-store device registry entry that resolves hardware-id, tenant-id, and site-id together:

```clojure
;; Config store — device registry (runtime, no deploy)
{:device/serial        "SERIAL123"
 :device/hardware-id   :growatt-cubewifi
 :device/tenant-id     "home"        ;; the owner / isolation boundary (ADR-026)
 :device/site-id       "home"        ;; the location; parallel inverters share this (ADR-026)
 :device/permission-id :default
 :device/label         "Main inverter"}
```

Unknown serials are rejected at the connection level — see ADR-020 for the full connection auth and device registration decision. Hardware-id, tenant-id, and site-id are bound to the Manifold stream at connection time and used for all subsequent packet dispatch and permission resolution. `tenant-id` drives `open-store` (the `TenantStore` is tenant-scoped); `site-id` is stamped onto each Reading this device produces.

### Full Ingestion Flow

```
Growatt CubeWiFi (outbound TCP)
    → Aleph TCP server (listens on configured port)
    → Manifold stream per connection
        → frame accumulation (length-prefixed packet)
        → CRC validation (reject and log on failure)
        → minimal server ack/keepalive emitted
        → hardware mapping dispatch (by :hardware/id on connection)
        → generic decoder (offsets + types from descriptor → values)
        → Malli validation of canonical Reading
        → write via solar.readings/write! (TenantStore — ADR-026)
        → put! onto core.async channel
    → pipeline consumer (ADR-007)
        → emit :new-reading signal
        → draft/update, detect solar events
```

Connection-level concerns on the Manifold stream:
- Hardware mapping dispatch (which descriptor to use for this device)
- Permission-id association (ADR-013) — which tenant/site this connection belongs to
- Reconnect and idle/timeout handling
- Forwarding toggle (if enabled, copy raw packet to Growatt endpoint)

## Rationale Summary
- Aleph: multi-device design intent requires non-blocking transport with per-connection lifecycle; migration from raw sockets would rewrite exactly the part Aleph already solves
- core.async handoff: decouples ingestion from pipeline, provides natural buffering
- Terminate + emulate: stable contract (device packet format), data-sovereign, no external uptime dependency
- Forwarding as config toggle: runtime decision, no deploy, default off
- Hardware mapping as code-only descriptor: byte-level protocol knowledge is not LLM territory; generic decoder keeps new inverter support as a descriptor addition not a code change

## Consequences
- (+) Scales to multiple devices, sites, and tenants without architectural change
- (+) Per-connection state gives a clean home for device identity, hardware mapping, and permission association
- (+) Generic decoder means new inverter support is a descriptor in code, not new parsing logic
- (+) Forwarding toggle gives optionality without committing to third-party dependency
- (-) Aleph/Manifold adds a dependency and a new conceptual model (streams vs threads) — developers must understand backpressure and stream lifecycle
- (-) Minimal server emulation requires understanding what acks/keepalives the CubeWiFi expects — this must be empirically verified and may need adjustment on firmware updates
- (-) The inverter has no independent time source — it sets its clock to exactly what TIME_SYNC sends. The server must send explicit UTC (not JVM default zone); a wrong timezone propagates to every inverter_timestamp until reconnect. Timezone conversion is the application's responsibility at display time.
- (-) Hardware mapping offsets are in code (deploy to change) — if Growatt changes its packet format in a firmware update, a deploy is required to update the descriptor
