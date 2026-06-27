# ADR-032: Reading Identity — `(device_serial, ts)` vs including `seq`

## Status
Draft — open decision, collected for analysis. Operating under the interim assumption (Option A), explicitly noted in TDD-02. (ADR-031 is reserved for the user-facing output surface, TDD-08, pending; this decision takes 032.)

## Context
ADR-002 fixes Reading identity as `device-serial + timestamp` (no surrogate id). TDD-02 instantiates that as the `readings` `PRIMARY KEY (device_serial, ts)`, which is also the `ON CONFLICT` target for idempotent `write!`.

The captured CubeWiFi packets (from the Sacolar device) carry a sequence number (`seq`, visible in capture filenames `..._seq000ce_...`) — device-originated and persistent across reconnects. It is stored on the Reading (`:reading/seq`) but deliberately excluded from identity.

Two tensions pull in opposite directions, and both choices have a real failure mode:

- **Timestamp-coarseness tension.** The device's timestamps can be coarse; back-to-back packets may share a `ts` while differing in `seq`. Under `ON CONFLICT (device_serial, ts) DO NOTHING`, a genuinely-distinct same-`ts` reading is silently dropped — *treated as a replay*, a loss of the second snapshot (not a duplicate row). This is a correctness gap that is invisible until it distorts a KPI.
- **Protocol-specificity tension.** `seq` is a CubeWiFi / protocol-framing artifact. The hardware-descriptor model (ADR-018 / ADR-030) means future inverters may not expose a wire sequence at all. Baking `seq` into identity couples identity to one protocol's wire format and may not be uniformly satisfiable across devices.

This is collected for analysis rather than resolved now, because the single Sacolar site has not yet produced evidence that decides it either way.

## Options Considered

- **A — `(device_serial, ts)`** *(current interim)*. PK on these two; `seq` stored only; idempotent ingest via `ON CONFLICT DO NOTHING`.
  - (+) No dependency on a wire-protocol field; works for any device with a clock + serial; minimal.
  - (−) Silent drop of genuinely-distinct same-`ts` readings; idempotency is "same logical reading by timestamp," which may be coarser than the device's actual push granularity.
- **B — `(device_serial, ts, seq)` with `seq` required from every protocol handler.** PK includes `seq`; handlers use the wire `seq` when present and synthesize a per-connection counter when absent.
  - (+) Disambiguates same-`ts` packets; identity matches the device's actual push granularity for the CubeWiFi push.
  - (−) Couples identity to a protocol field; a *synthesized* `seq` does not survive reconnect, so a no-wire-seq device loses cross-reconnect replay-dedup (idempotent ingest only partial for such devices); every handler must implement the `seq` contract.
- **C — No uniqueness constraint; dedup at read.** Append freely; reads dedup by identity.
  - (+) No silent drop; replays stored and visible (auditable).
  - (−) Storage bloat from replays; idempotent ingest is not storage-enforced; dedup logic duplicated across read paths; tension with the "stored facts" cleanliness model.
- **D — Payload-hash identity.** PK on `(device_serial, ts, hash(payload))` (or `hash(payload)` alone).
  - (+) Detects exact-duplicate replays regardless of `ts`/`seq`.
  - (−) A re-push with a drifted field (e.g. `energy-total` advanced) would not match and would insert; does not solve same-`ts`-distinct; adds a column and compute.

## Decision
**Open.** Operating under **Option A** as the interim assumption, explicitly noted in TDD-02's `readings` DDL and invariants. Revisit on any trigger below; the chosen option becomes a new Accepted ADR (or this one flipped to Accepted) — a deliberate, versioned change, not a silent edit.

## What would resolve it (analysis / data needed)
- **Capture evidence (auto-collected).** Inspect real CubeWiFi packets for same-`ts` distinct-`seq` readings from one device within a short window. This is **collected in normal operation, not by manual capture**: `write!` dead-letters any *differing* same-`(device_serial, ts)` reading as `identity-conflict` (TDD-02), so the `dead_letter_readings` table *is* the capture — query it. If `identity-conflict` rows appear → Option A is retaining only the first of conflicting readings, and Option B (or the device-originated key) becomes necessary. If none appear across a representative period (a full day including re-pushes and reconnects) → Option A's assumption is empirically safe for this device.
- **Replay / reconnect behaviour.** Confirm whether the device re-pushes after reconnect reusing the original `ts`+`seq` (clean dedup under A) or advancing `seq` (would collide under A, dedup cleanly under B).
- **Second-protocol analysis.** When a second inverter protocol is evaluated, determine whether it exposes a device-originated sequence. If yes universally → Option B is cheap and uniform; if some lack it → Option B's synthesized-seq gap must be accepted, or Option A/C retained.

## Triggers to decide
- A capture showing same-`ts` distinct-`seq` from one device → decide immediately (likely B, or a CubeWiFi-specific identity supplement).
- A second inverter protocol arriving → decide before implementing its handler.
- Unexplained KPI gaps that could stem from dropped same-`ts` readings → investigate, then decide.

## Consequences (interim, under A)
- (+) Identity depends only on clock + serial, not a protocol-specific field.
- (+) Idempotent ingest is storage-enforced for exact `(device_serial, ts)` replays.
- (−→ mitigated) Differing same-`ts` readings are **dead-lettered as `identity-conflict`, not silently dropped** (TDD-02) — collected for this analysis, the first row kept. Residual risk: the *first* of two same-`ts` readings is the one retained; if the second was truer it is quarantined (recoverable from dead-letter), not lost. The silent-drop failure mode is mitigated, not eliminated, until the identity decision is made.
- (−) The assumption is device-specific (Sacolar / CubeWiFi) and unverified; it does not generalise automatically to future devices.

## References
- ADR-002 — domain entity model / Reading identity
- ADR-018 — ingestion, hardware mapping; `seq` in the descriptor
- ADR-030 — descriptor-as-data, no per-model namespace
- TDD-02 — `readings` DDL and the *Identity assumption — noted* block (the interim instantiation of this decision)