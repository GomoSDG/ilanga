# 01 — Ingestion & Device Identity

**Status:** Stub — skeleton only; implementation detail pending.

## Purpose & scope
The path from a Growatt CubeWiFi TCP connection to a canonical `Reading` written to DuckDB and handed to the engine as `:new-reading`. Covers the TCP server, the Growatt wire protocol (framing, XOR obfuscation, CRC16), the hardware-mapping descriptor that extracts fields from a decoded payload, device registration/auth, and the core.async handoff to the pipeline. **Excludes** the pipeline dispatch and KPI computation (03) and the storage layout (02).

## Governing ADRs
- ADR-018 Ingestion TCP server & hardware mapping — Accepted
- ADR-020 Connection auth & device registration — Accepted

## Interfaces
TODO:
- Aleph TCP server: accept → wait for announce (timeout ~10s) → serial lookup in device registry → bind `hardware-id`/`tenant-id`/`site-id`/`permission-id` to the Manifold stream → `open-store(tenant-id)` → `TenantStore` (ADR-026) → time-sync (`0x18`) → keepalive loop → normal packet processing. `site-id` is stamped onto each Reading this connection produces.
- Hardware-mapping descriptor shape (`:hardware/id`, `:hardware/protocol`, `:hardware/mappings` — offset/type/scale vectors).
- Canonical `Reading` map emitted on `:new-reading` (namespaced keys, units per ADR-009).
- core.async channel: the single handoff point from ingestion to the engine — its capacity, backpressure, and what happens on overflow/drop.

## Data structures / schemas
TODO:
- Growatt packet framing (`[seq 2B BE][proto 2B][len 2B][unit 1B][type 1B][XOR payload][CRC16 2B BE]`), XOR key `b"Growatt"` for `proto 0x0006`, CRC16 Modbus poly `0xA001` init `0xFFFF`.
- Device registry entry (`:device/serial`, `:device/hardware-id`, `:device/tenant-id`, `:device/site-id`, `:device/permission-id`, `:device/label`) — the one lookup that resolves the whole connection identity (ADR-020). `tenant-id` drives `open-store`; `site-id` stamps readings; parallel inverters share a `site-id`.
- Hardware-mapping descriptor (developer-authored only — no LLM catalog entry; explicit exception to ADR-005).
- Field extraction table: the verified offsets (pv1 79, pv2 83, load 91, grid 145, battery-soc 107, battery-power 231 signed, energy-today 174, energy-total 175 u32, temp 121) — **flag energy-today width as unverified for high-yield days** (ADR-018 / register-map memory).

## Sequences / flows
TODO:
- Connection lifecycle (ADR-020 §Connection lifecycle): no DuckDB write and no hardware-id dispatch before the serial lookup succeeds.
- Decode pipeline: raw bytes → de-frame → CRC check → XOR de-obfuscate → hardware-mapping descriptor extracts fields → scale → canonical `Reading` → core.async → `:new-reading`.
- Forward-to-Growatt-cloud (terminate-with-emulation) as a config toggle, default off.
- New inverter model (same protocol) = new descriptor = deploy; no parsing-logic change.

## Invariants & error modes
TODO:
- Unknown serial → stream closed + logged with serial (the discovery path for adding a device); never silently accepted.
- CRC/XOR/length failures → stream closed, logged; no partial writes.
- Announce timeout → stream closed.
- The protocol layer (framing/XOR/CRC) is per-protocol code, **not** in the descriptor — keep the descriptor to field extraction only.
- Backpressure: define the policy when the engine can't drain the ingestion channel (drop oldest? block? — decision needed).

## Open / deferred
- energy-today field width: verify uint8 vs u16-at-173-174 on a >25.5 kWh summer capture.
- Terminate-with-emulation forwarding: behaviour and config surface when enabled.
- Backpressure policy on the ingestion→engine channel.