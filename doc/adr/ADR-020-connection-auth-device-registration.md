# ADR-020: Connection Auth and Device Registration

## Status
Accepted

## Context
ADR-013 leaves open how a session or connection gets its `permission-id`. ADR-018 leaves open how a fresh TCP connection is resolved to a `hardware-id`, `tenant-id`, and `site-id`. Both gaps converge at the same moment: the device opens a TCP connection, sends an announce packet, and the server must decide (a) whether to accept the connection at all, (b) which hardware descriptor to use for decoding, and (c) which permission set applies to readings from this device.

The Growatt CubeWiFi presents no credential — it sends a serial number in the announce packet. That is the only identity token the device offers.

The threat model is a home LAN: the perimeter is the local network. The authentication question is "is this a device we registered?" not "did it prove its identity cryptographically?"

## Decision

### Serial allowlist in the config store (device registry)

Authentication is serial-based allowlisting. A device is authorised if and only if its serial number appears in the config-store device registry. The registry entry also carries everything the server needs to fully resolve the connection identity:

```clojure
;; Config store — device registry entry
{:device/serial        "SERIAL123"
 :device/hardware-id   :growatt-cubewifi   ;; → ADR-018 hardware mapping
 :device/tenant-id     "home"              ;; → the owner / isolation boundary (ADR-026); drives open-store
 :device/site-id       "home"              ;; → which site's rows; parallel inverters share this (ADR-026); stamped on each Reading
 :device/permission-id :default            ;; → ADR-013 permission set for this device's data
 :device/label         "Main inverter"}
```

Adding a device = writing a registry entry (runtime, no deploy). Removing it = deleting the entry; the device is rejected on its next reconnect.

### Connection lifecycle

```
Device opens TCP connection
    → Aleph accepts socket → Manifold stream created
    → server waits for announce packet (timeout: configurable, default 10 s)
        timeout or malformed announce → stream closed, logged
    → serial extracted from announce packet
    → serial looked up in device registry
        not found → stream closed, logged with serial (enables addition to registry)
        found →
            hardware-id, tenant-id, site-id, permission-id resolved from registry entry
            open-store(tenant-id) → TenantStore bound to stream; site-id carried to stamp on readings  ;; ADR-026
            time-sync response sent (0x18 packet — keeps device clock aligned)
            keepalive loop started
            stream enters normal packet processing (ADR-018 flow)
```

No data is written to DuckDB before the serial lookup succeeds. No hardware-id dispatch occurs before the serial lookup succeeds. No `TenantStore` is constructed before the serial lookup succeeds — `tenant-id` is unknown until the registry entry is read, and `open-store` needs it (ADR-026).

### Permission-id resolution for LLM interfaces

Device connections carry a `permission-id` for readings-level access (which tenant's data can be queried; site scoped via query). LLM interface sessions (MCP and internal chat) carry a `permission-id` resolved separately at session establishment:

- **MCP**: permission-id is configured in the MCP server config (which the user sets up when authorising the Claude Desktop connection). Default: `:default`. Admin sessions require explicit config.
- **Internal chat**: permission-id is set at session creation — typically `:default` for the home user, `:admin` when the user authenticates through the in-app settings flow.

Both resolve to the same permission descriptor in the config store (ADR-013), which also carries `:permission/tenant-id` — so an LLM session is bound to one tenant's data exactly as a device connection is (ADR-026). The device registry `permission-id` is a third source — used to restrict what a connection can do if the same permission system is ever extended to device-initiated actions (not currently planned).

### What "authentication" means and does not mean here

Serial-based allowlisting is identity-by-registration, not cryptographic proof. An attacker on the LAN who knows the serial could spoof a connection. This is an accepted risk for a home system where:
- The LAN perimeter is the primary security boundary
- The inverter is a trusted device on a trusted network
- The consequence of a spoofed reading is incorrect solar data, not financial or safety harm

If the threat model changes (e.g. the server is exposed over the internet), TLS with client certificates is the upgrade path. The connection lifecycle above is the right place to add that check — the rest of the system is unaffected.

## Rationale
- Serial-based allowlisting is the minimum viable auth that prevents unknown devices from polluting DuckDB — adequate for the home LAN threat model
- Putting hardware-id, tenant-id, site-id, and permission-id in one registry entry means the connection lifecycle makes one lookup and has everything it needs — no secondary lookups mid-stream
- Adding/removing devices at runtime (no deploy) is consistent with ADR-004/005: the allowlist is config data, not code
- Unknown serials are logged with the serial value — this is the mechanism by which a new device can be discovered and then registered
- Closing ADR-013's gap here (not in ADR-013 itself) keeps the permission ADR focused on what permissions do, not how sessions acquire them

## Consequences
- (+) Unknown devices are rejected before any data flows — no silent pollution of DuckDB
- (+) One config store lookup resolves hardware-id, tenant-id, site-id, and permission-id simultaneously
- (+) Adding a new device is a runtime config change — no deploy, no restart
- (+) Unknown serial is logged with its value — self-documenting discovery path for new devices
- (+) Closes both open gaps: ADR-013 (how sessions get permission-id) and ADR-018 (how connections get hardware-id/tenant-id/site-id)
- (-) Serial-based auth is not cryptographic — a knowledgeable attacker on the LAN could spoof; accepted for home LAN threat model
- (-) The announce packet timeout must be tuned — too short breaks reconnects on slow boot, too long holds a socket open
- (-) If the device registry entry is deleted while a connection is live, the existing stream continues until disconnect; there is no mid-stream revocation
