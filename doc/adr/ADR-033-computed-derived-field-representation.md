# ADR-033: Computed & Derived Field Representation in Hardware Descriptors

## Status
Accepted

## Context
ADR-018 fixes that the hardware descriptor is pure data and that some Reading fields are not single-offset `{:offset :type :scale}` triples — battery power/current are multi-register, direction-dependent, overflow-sensitive; pv-total is arithmetic over two decoded fields. ADR-018 left *how* such fields are represented in the descriptor as an open fork ("not decided here"). This ADR resolves it.

Two pressures shaped the decision:

- **The descriptor must be the complete field map.** Every Reading key a device produces must be visible from the edn. The alternative — computed fields produced in the handler, absent from the descriptor — makes canonical Reading fields materialize in code ("magic"); a reader of the edn alone cannot see them. Rejected.
- **The descriptor must be the complete offset map.** The byte offsets a device uses must be visible from the edn, so a protocol-doc offset change is an edn edit, not a code edit. Offsets buried in a codec fn re-introduce the same split in a lesser form.

## Decision

The descriptor carries three field classes, decoded in order `:fields` → `:compute` → `:derive` → write.

### `:fields` — single-offset extract
Unchanged from ADR-018: `{:reading-key :offset :type :scale}`; the generic decoder reads, types, and scales. One byte range, one type, one scale (e.g. pv1-power-w, energy-total-kwh, temp-c).

### `:compute` — multi-register, from payload, codec fns
Fields the triple cannot express. Each entry declares the algorithm (`:fn`) and its **inputs** (the input registers + any parameters); the codec fn is pure `(payload, inputs) → value`:

```edn
:compute [{:reading-key :reading/battery-power-w
           :fn :ilanga.protocol.sacolar.codec/battery-power
           :inputs {:power-mag 231 :charge-i 241 :discharge-i 243 :idle-flag 230 :hysteresis-a 0.5}}]
```

- **Offsets in the descriptor** (`:inputs`), not in the fn — the descriptor is the complete offset map; a protocol-doc offset change is an edn edit.
- **Per-protocol codec namespaces** (`ilanga.protocol.sacolar.codec`): computed-field logic is protocol-specific (it assumes the payload layout), so fns live with the protocol code. There is no shared `ilanga.protocol.codec` fn dump; the reusable part is the dispatch, not the fns.
- **Multimethod dispatch.** A single `defmulti compute-field` in the generic decoder dispatches on the `:fn` keyword; each protocol's `.codec` namespace defines its methods with `defmethod`. `defmethod` *is* registration — the protocol namespace is self-contained; no central registry file to keep in sync. Startup validates that every loaded descriptor's `:fn` keys have a method (`get-method` non-nil) — fail-closed at boot, not at first decode.

```clojure
;; ilanga.protocol.decoder
(defmulti compute-field (fn [fn-kw _payload _inputs] fn-kw))

(defn decode-computed [descriptor payload]
  (into {} (for [{:keys [reading-key fn inputs]} (:compute descriptor)]
             [reading-key (compute-field fn payload inputs)])))
```
```clojure
;; ilanga.protocol.sacolar.codec
(defmethod ilanga.protocol.decoder/compute-field :ilanga.protocol.sacolar.codec/battery-power
  [_kw payload {:keys [power-mag charge-i discharge-i idle-flag hysteresis-a]}]
  ;; overflow-aware cond — see protocol doc "Battery power & current decode"
  ...)
```

### `:derive` — declarative ops over decoded fields, stored
Fields derived from *already-decoded* Reading values, no payload (e.g. pv-total = pv1 + pv2). Canonical Reading fields, but not measured facts — computed at ingest and **stored as columns** (plain column reads; no recompute-on-read; no write/read rule-drift). Declarative ops over reading keys; no fns:

```edn
:derive [{:reading-key :reading/pv-total-power-w :op :sum
          :of [:reading/pv1-power-w :reading/pv2-power-w]}]
```

- `:derive` is protocol-agnostic (`pv1+pv2` means the same for any two-string inverter). It is co-located in the hardware descriptor for single-file simplicity, accepting a one-line duplication across two-string descriptors.
- **Declarative ops only.** An fn escape-hatch is *deferred* — not added until a real derived field needs logic a declarative op cannot express (a conditional or a lookup curve). pv-total is a sum; data suffices.
- Derived fields are stored, so they appear in the `readings` DDL and the Malli schema, but are not "physical facts" — a documented divergence from the "only physical facts are stored" framing, accepted for simplicity (see Consequences).

### Decode order
`:fields` (extract) → `:compute` (over payload) → `:derive` (over the decoded Reading) → Malli validate → `ilanga.domain.readings/write!`.

## Alternatives considered
- **Handler-injected computed fields (fork A).** Computed fields produced in the handler, absent from the descriptor. Rejected: the descriptor stops being the complete field map — canonical fields materialize in handler code ("magic"); the edn alone does not show them.
- **Offsets in the codec fn (fork B).** `:compute` with `:fn` only, offsets hardcoded in the fn. Rejected: the descriptor is the complete field map but *not* the complete offset map — battery offsets split into code, the same indirection in a lesser form.
- **Plain keyword→fn registry map (vs multimethods).** An explicit registry map keyed by `:fn`. Rejected: a two-place edit (define fn + register) and a central registry file; the protocol namespace would not be self-contained. Multimethods make `defmethod` the registration, keeping each protocol's fns in its own namespace.
- **Fn-based `:derive` (multimethod, parallel to `:compute`).** Rejected as unnecessary indirection: pv-total is a sum, so a declarative op is simpler and less error-prone than a fn. An escape-hatch can be added later if a derived field ever needs logic.

## Consequences
- (+) The descriptor is the complete field map *and* the complete offset map — nothing materializes in code; a protocol-doc offset change is an edn edit.
- (+) Protocol namespaces are self-contained — adding a computed field is a `:compute` entry + a `defmethod` in the protocol's `.codec` ns; nothing central changes.
- (+) Derived fields are stored → reads are plain column lookups; no recompute-on-read, no write/read rule-drift.
- (-) Derived (recomputable) values are stored, diverging from the "only physical facts are stored" principle. Accepted: for cheap, stable values like pv-total the simplicity and read-time savings outweigh recomputation-for-auditability. The divergence is documented, not silent.
- (-) `:derive` rules are protocol-agnostic yet co-located in per-model descriptors — `pv1+pv2` duplicates across every two-string descriptor. Accepted (one line each) for single-file simplicity.
- (-) `defmulti` reload semantics in dev-REPL: re-evaluating the decoder ns clears the method table. Manageable with dev tooling (guard the `defmulti` / a `re-register-codecs!` helper in `dev/`), not an app-path concern; non-issue under AOT.
- (-) Startup must validate descriptor `:fn` keys against the multimethod (fail-closed) — a missing method is a boot error, not a first-packet error.