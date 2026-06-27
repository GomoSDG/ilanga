# ADR-034: Framing Descriptor Vocabulary & Data-Driven Framer

## Status
Accepted

## Context
ADR-018 settled that framing is data-driven from the descriptor's `:framing` block (a generic framer at the connection), not per-protocol code — but left the *vocabulary* of `:framing` undefined. The framer must recover packet structure from raw stream bytes — delimit by length, verify CRC, de-obfuscate, emit a parsed packet — parameterized only by the descriptor, so Growatt (and any length-prefixed + CRC + XOR peer) needs no framing code. This ADR defines that vocabulary.

Two pressures, paralleling ADR-033's for `:compute`/`:derive`:

- **Complete and self-describing.** The `:framing` block must let a reader (and the framer) see the full header layout and how `length` maps to `payload_len` — no magic constants hidden in framer code.
- **Bounded, not a general framing DSL.** The vocabulary covers the common length-prefixed family; an fn escape-hatch exists for exotic framing, deferred until a real protocol needs it (mirroring `:derive`'s escape-hatch in ADR-033).

## Decision

The `:framing` block:

```edn
:framing
{:header      [[:seq 2 :be] [:proto 2 :be] [:length 2 :be] [:unit 1] [:type 1]]
 :length      {:counts [:unit :type :payload]}
 :crc         {:algorithm :crc16-modbus :poly 0xA001 :init 0xFFFF
               :input-order :be :covers :header+payload :width 2}
 :obfuscation {:when {:proto 0x0006} :algo :xor-repeating :key "Growatt" :applies-to :payload}}
```

### `:header` — sequential width declarations
An ordered vector of `[name width endian?]` triples. The framer derives each field's offset by accumulating preceding widths (header is contiguous) — compact, no redundant offsets. All five fields are exposed in the emitted packet map: the connection routes on `:type`; the handler echoes `:seq`/`:unit`/`:type` in ACKs.

### `:length` — `:counts` semantics
`{:counts [...]}` names the components the length field sums. `payload_len = length − Σ(widths of non-:payload counts)`. For Growatt `:counts [:unit :type :payload]` ⇒ `payload_len = length − 1 − 1 = length − 2`. Self-documenting — the `−2` is never a bare magic constant; it falls out of the named header widths. Total packet size = `Σheader widths + payload_len + :crc :width`.

### `:crc` — verify over wire bytes
`algorithm`/`poly`/`init`/`input-order` specify the CRC; `:covers :header+payload` means over bytes `[0, header_size + payload_len)` — the header plus the still-encrypted wire payload (CRC is computed *before* XOR, so "encrypted-payload" is automatic); `:width` is the trailing CRC field width and the trailer the framer strips.

### `:obfuscation` — trigger + algo + key + scope
`{:when {<field> <value>} :algo :xor-repeating :key ... :applies-to :payload}`. The framer de-obfuscates only when the named header field equals `:when`'s value (Growatt: `proto == 0x0006`). `:applies-to :payload` — the header is plaintext. Header obfuscation is not in the vocabulary (defer to the escape-hatch if ever needed).

### Framer behaviour (parameterized only by `:framing`)
1. Parse header fields (sequential widths → offsets).
2. `payload_len = length − Σ(widths of non-:payload :counts)`.
3. Accumulate until `header_size + payload_len + crc.width` bytes.
4. Compute CRC per `:crc` over `[0, header_size + payload_len)`; reject on mismatch (close + log, no partial emit — TDD-01).
5. If `:obfuscation/when` matches, XOR the payload slice with the repeating `:key`.
6. Emit `{…header-fields :payload <decrypted bytes>}` to the connection, which routes on `:type`.

### Escape-hatch — deferred
A `:framer-fn` key (a per-protocol fn taking raw stream → parsed packets) is *deferred*, mirroring `:derive`'s escape-hatch: not added until a real protocol's framing the declarative vocabulary can't express. Growatt needs none.

### Placement
The framer is a generic component at the **connection** (the stream owner); the connection drives it and routes its output (ADR-018 / TDD-01). It is protocol-agnostic — Growatt knowledge lives only in the `:framing` data.

## Alternatives considered
- **Explicit per-field offsets** (`{:name :offset :width :endian}`). Rejected for the common case: contiguous headers make offsets redundant, and keeping offsets consistent with widths invites error. Explicit offsets remain reachable via the fn escape-hatch for headers with gaps.
- **Direct length adjustment** (`{:payload-len-adj -2}`). Rejected: terse, but a bare `−2` is an unexplained magic constant; `:counts` derives the same number from named header widths and reads as "the length field counts unit+type+payload."
- **Flat keys** (`:proto-xor`, `:xor-key`) as in the prior placeholder shape. Rejected: no header layout, no length semantics — a generic framer cannot be written from it.
- **Per-protocol framing code (no `:framing` block).** Rejected by ADR-018: framing must be data-driven so a protocol-doc change is an edn edit and the framer component stays protocol-agnostic.
- **A general framing DSL now.** Rejected as premature: the bounded vocabulary + deferred escape-hatch cover the real protocols; a fuller DSL is speculative complexity (the same principle that deferred `:derive`'s fn path in ADR-033).

## Consequences
- (+) The framer is fully data-driven; Growatt needs no framing code — `:framing` transcribes the protocol-doc framing section.
- (+) `:counts` makes the length↔payload relationship self-documenting; no magic constants.
- (+) Header layout is visible from the edn alone (the "complete map" principle, extended to framing).
- (-) The vocabulary is bounded — a protocol outside the length-prefixed + CRC + payload-XOR family needs the deferred `:framer-fn`. Acceptable: the escape-hatch mirrors `:derive`; Growatt and likely peers are covered.
- (-) Sequential-width headers can't express gaps without the escape-hatch (explicit-offsets would). Acceptable: contiguous headers are the norm; the escape-hatch is the documented path for the rest.
- (-) One more descriptor section to validate at startup — `:framing` is required for any descriptor; the framer fails closed on a missing/malformed `:framing` at boot, not at first packet.