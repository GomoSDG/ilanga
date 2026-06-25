# Ilanga

A residential solar / PV monitoring system, written in Clojure on the JVM. It ingests live inverter telemetry over TCP, stores it as immutable time-series facts, computes energy KPIs and billing metrics deterministically, and delivers interpreted analysis through an LLM interface that never sits in the execution path.

**Name:** *Ilanga* — Nguni (isiZulu/isiXhosa) for *sun* / *day*. Pronounced *ee-LAH-ngah*.

## Working directory layout

This repo mixes two things; keep them straight:

- **`doc/`** — the design. This is the tracked, authoritative artifact.
  - `doc/adr/` — Architecture Decision Records (`ADR-001` … `ADR-029`). Each owns one decision; `Status:` is `Accepted` / `Draft`. The model ADRs (esp. **ADR-026** multi-tenant storage, **ADR-008** persistence, **ADR-002** entity model) are load-bearing — read before touching that area.
  - `doc/design/` — Technical Design Docs (`00-architecture` … `07-runtime-platform`). `00` is the composition view; `01`–`07` expand subsystems. Several are still **stubs** (skeleton only).
  - `doc/protocol/` — inverter wire-protocol reference (Growatt CubeWiFi).
- **Python prototypes at the repo root** (`growatt_*.py`, `dashboard.log`, `simulator.py`, `cost_analysis.py`, `visualize.py`, `auto_investigate.py`, …) and the `data/`, `proxy_data/`, `server/`, `templates/` dirs — **exploratory scratch from before the Clojure design existed.** They are *not* the implementation and are *not* tracked. Treat them as reverse-engineering notes / data sources, not as code to extend. The Clojure implementation has not started yet.

## The architecture in one line

LLM authors declarative programs (rules, KPIs, templates) → deterministic engine runs them against measured data → results loop back to the LLM as pre-assessed context packages for interpretation. The LLM authors and interprets; it **never executes**.

Two layers: a **data layer** of immutable facts, and a **meta layer** where registry = code/deploy and config = data/runtime.

## Tenancy model (do not get this wrong)

Three levels: **tenant** (owner / isolation boundary) → **site** (physical location; real local `site_id` column) → **device** (one inverter, one TCP connection; `device-serial` on the `Reading`).

- `tenant_id` is the structural isolation boundary, **deferred to cloud**. The domain **never sees or filters `tenant_id`**.
- `site_id` is a **real local column on every per-site row**, freely domain-filtered.
- All data access goes through a `TenantStore` (binding record, not interface); `open-store` is the only construction point. See **ADR-026**.

## Standing constraints (always apply)

- **Determinism.** Same descriptors + same data → same KPIs, always. The LLM is never in this path. Recomputation is auditability.
- **Credential isolation (ADR-025).** Mobele portal credentials **never** enter pv-app. External-data jobs run outside; the system only receives results through its ingest boundary.
- **Timezone.** User is UTC+2 (Johannesburg). DB timestamps are stored UTC and **always reported in local time**. The solar `Day` is bounded by solar events (first-sun → last-sun), not midnight (ADR-003); `Overnight` is last-sun → next-first-sun.
- **Immutability.** Readings are facts of physical reality — they cannot be corrected, only supplemented.

## When making changes

- Match the surrounding doc voice (terse, declarative, `Status:`/section conventions of the ADRs and TDDs).
- An ADR change that contradicts an Accepted ADR is a new decision — update the ADR (and ripple through the design docs), don't silently edit.
- The Python tree is not tracked; do not expand it as if it were the implementation. If implementation begins, it begins in Clojure per `doc/`.

## Useful pointers

- Start at `doc/design/00-architecture.md` for the composition view.
- `doc/adr/README.md`-style index does not exist yet; the `doc/adr/` directory is numbered and self-describing.
- Current site (the proof-of-concept tenant `home`): 10×550W panels, 5.5 kWp, Growatt CubeWiFi inverter, Johannesburg.