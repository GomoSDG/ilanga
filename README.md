# Ilanga

A residential solar / PV monitoring system, in Clojure on the JVM.

Ilanga ingests live inverter telemetry over TCP, stores it as immutable
time-series facts, computes energy KPIs and billing metrics deterministically,
and delivers interpreted analysis through an LLM interface that authors and
interprets but never executes.

**Name:** *Ilanga* — Nguni (isiZulu/isiXhosa) for *sun* / *day*.

## Status

Design phase. The architecture is documented; the Clojure implementation has
not begun. The Python files at the repo root are exploratory prototypes /
reverse-engineering scratch from before the design existed — they are **not**
the implementation and are not tracked.

## The design

- [`doc/design/00-architecture.md`](doc/design/00-architecture.md) — start here;
  the composition view of all subsystems.
- [`doc/adr/`](doc/adr/) — Architecture Decision Records (`ADR-001`–`ADR-029`),
  each owning one decision.
- [`doc/design/`](doc/design/) — Technical Design Docs (`01`–`07`) expanding
  each subsystem (several are still stubs).
- [`doc/protocol/`](doc/protocol/) — inverter wire-protocol reference
  (Sacolar inverter via CubeWiFi datalogger).

See [`CLAUDE.md`](CLAUDE.md) for the standing constraints (determinism,
credential isolation, UTC-vs-local timezone, immutability) and the tenancy model
(tenant → site → device).