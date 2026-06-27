# ADR-037: Push-Stream Control-Routing Descriptor Vocabulary

## Status
Accepted (lands with the push-stream handler code).

## Context
ADR-036 separated the connection families and scoped the build now underway to **push-stream**. ADR-034 made framing data-driven (`:framing`); ADR-033 made field offsets/compute data-driven. The remaining per-protocol facts the push-stream handler needs are **control routing**: which frame type announces identity, which types are telemetry, how to acknowledge, how to format the server→device TIME_SYNC and IDENTIFY frames. Without a vocabulary for these, the handler hardcodes CubeWiFi type bytes and ack shapes — over-fitting a family handler to one protocol and forcing code edits per new push-stream device.

## Decision
A `:push-stream` block in the hardware descriptor carries the per-protocol control-routing facts; the family handler (`ilanga.protocol.push-stream`) is data-driven off it. This mirrors ADR-034 (framing bytes in data, framing logic in the framer) and ADR-033 (offsets in data, algorithms in the codec): control-routing *facts* in the descriptor, control-routing *logic* in the handler.

```edn
:push-stream
{:server-proto  0x0006
 :server-unit   0x01
 :announce-type 0x03
 :data-type    #{0x04 0x50}
 :ack          {:applies-to #{0x16 0x03 0x04 0x50}
                :payload    {0x16 :echo-received 0x03 :zero 0x04 :zero 0x50 :zero}}
 :time-sync    {:type 0x18 :payload-format :ascii-utc :trigger :after-announce}
 :identify     {:type 0x19 :payload-format :read-registers-0x0004 :trigger :on-first-frame}}
```

- `:announce-type` — the device→server frame that establishes identity (serial read from `:serial`). **0x03** for CubeWiFi.
- `:data-type` — the set of telemetry types routed to the packet-channel. **0x04** = DATA, **0x50** = BUFFERED_DATA (same payload layout, replayed on reconnect).
- `:ack` — which types the server acks, and the per-type payload shape: `:echo-received` (re-encode the received payload — the PING raw-echo) or `:zero` (a single 0x00 — the standard ack). The header is always echoed (seq/proto/unit/type); XOR + CRC are applied by the framer's encoder (ADR-034). PING (0x16) is `:echo-received`; ANNOUNCE/DATA/BUFFERED_DATA are `:zero`.
- `:server-proto` / `:server-unit` — the proto (0x0006, the obfuscation trigger) and unit (0x01) the server speaks on outbound frames.
- `:time-sync` / `:identify` — server→device frames: type, `:payload-format` (a named format the handler implements), and `:trigger`. An unknown format fails closed at the handler call, mirroring `:compute` fns failing closed at boot.

### Handshake sequence — coded, not declarative (deferred)
The descriptor carries the per-protocol *facts*; the handshake *sequence* — first frame → IDENTIFY; ANNOUNCE → ack + TIME_SYNC + identity-resolved; telemetry → ack + forward — is **coded in the handler** (`handshake-step`), not a declarative state machine in the descriptor. Rationale: across push-stream families the *pattern* is shared (device announces, server acks, server time-syncs) but the *sequence* differs (CubeWiFi's first-frame→IDENTIFY ≠ Solarman's login handshake). With one protocol we lack evidence that a declarative handshake vocabulary would be family-shared, so building one would be speculative — the same "don't decide before evidence" principle as ADR-032. When a 2nd push-stream device lands, the sequence is the prime candidate to reconsider for promotion to data.

### Scope
Push-stream family only (ADR-036). The handler implements the payload formats and ack shapes CubeWiFi uses (`:ascii-utc`, `:read-registers-0x0004`, `:echo-received`, `:zero`); a new format or shape is handler code — mirroring the `:compute`/`:framer-fn` escape-hatches. A family-dispatch mechanism is deferred (ADR-036).

## Consequences
- (+) A new push-stream device with different type bytes / ack shape / time-sync format is a new descriptor `:push-stream` block, no handler edit — *if* its facts fit the vocabulary.
- (+) The handler is family-shared and parameterized; CubeWiFi is one instance, not the handler's identity.
- (+) Control-routing facts are visible from the edn alone — the "complete map" principle, extended from framing (ADR-034) and fields (ADR-033) to control.
- (−) The vocabulary is bounded — a push-stream protocol with a control-routing fact it cannot express (a novel ack shape, a multi-step time-sync) needs handler code. Acceptable: mirrors `:compute` / `:framer-fn`.
- (−) The handshake sequence is coded, so a 2nd push-stream protocol with a different sequence may need handler changes — deferred until evidence (above).

## Open / deferred
- Declarative handshake-sequence vocabulary — reconsider at the 2nd push-stream device.
- Ack payload shapes / payload formats beyond `:echo-received`, `:zero`, `:ascii-utc`, `:read-registers-0x0004` — added as real protocols need them.
- The connection/Aleph layer that drives `handshake-step` and materializes its actions (next chunk, ADR-028).

## References
- ADR-018 — ingestion TCP server & hardware mapping (push-stream design)
- ADR-033 — computed & derived field representation (the data-driven-pattern precedent)
- ADR-034 — framing descriptor vocabulary (the sibling this ADR extends)
- ADR-036 — connection family taxonomy (the family scope this ADR serves)