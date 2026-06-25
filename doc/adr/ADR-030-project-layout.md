# ADR-030: Project Layout — Build Tool and Namespace Architecture

## Status
Accepted

## Context
ADR-001 fixes Clojure on the JVM (and names the core libraries — `next.jdbc`, `Malli`, `honey.sql`, `core.async`) but says nothing about the build tool or how the codebase is organised into namespaces. The architecture has a two-layer separation (ADR-004: data vs meta) and a determinism boundary (ADR-014: the LLM never executes; everything inside the boundary is pure). How those seams are *expressed in the code* — which build tool, which namespace roots, how the seam is enforced — is undecided. ADR-026's illustrative domain calls used a `solar.*` namespace root that was never actually chosen.

This ADR owns the project layout decision. The per-subsystem namespace lists and the directory tree are TDD-level detail (TDD-00 states the rules; TDDs 01–07 name their own namespaces); this ADR fixes the build tool, the roots, and the enforcement mechanism.

## Options Considered

### Build tool
- **Leiningen** — batteries-included (`lein repl`, `lein uberjar`, profiles); `project.clj` is eval'd code, more convention and magic.
- **deps.edn + tools.build** (Clojure CLI) — declarative, minimal; dev/test/nrepl assembled via aliases; packaging via `tools.build`.
- **Polylith** (on deps.edn) — enforces a brick/component architecture with explicit interfaces; the subsystem seams (01–07) map ~1:1 to bricks, so the build would enforce the seam discipline.

### Namespace roots
- **Single-root flat** — `ilanga.readings`, `ilanga.days`, `ilanga.engine`, … all at one level; seam by naming convention only.
- **Nested** — `ilanga.domain.*` (data layer) + `ilanga.*` top-level (meta + adapters); the data layer is a visible subtree.
- **Dual-root** — `solar.*` (a generic domain) + `ilanga.*` (the product); honors the `solar.*` names used illustratively in ADR-026.

## Decision
**deps.edn + `tools.build`**, single project. Entry point `ilanga.main/-main` via a `:run` alias.

**Nested namespace roots**, both under the project name, mapping to ADR-004's two layers:
- **`ilanga.domain.*` — the data layer.** Facts, entity schemas, pure KPI/Period computation, the Malli entity registry, and the persistence *port* (the `TenantStore` record). Pure: depends only on libraries and other `ilanga.domain.*` namespaces.
- **`ilanga.*` (top-level, non-`domain`) — the meta layer + adapters.** Ingestion and protocol decoding, engine orchestration, the LLM surface, registry/config, billing orchestration, runtime, the persistence *adapter* (`ilanga.db`, which owns `open-store`), and `main`. May depend on `ilanga.domain.*`.

**The seam is enforced by a namespace-dependency lint rule**, not convention: `ilanga.domain.*` may never require an infra namespace; infra may require domain. This is what prevents the data layer drifting into the meta layer. The seam is also the determinism boundary (ADR-014): everything pure and inside the boundary is `ilanga.domain.*`; the boundary and everything outside is `ilanga.*`.

**Directory == namespace.** Namespace segments map to path segments, so the rules above are the layout; no separate tree is specified.

**Entity schemas are co-located** with their repository functions (`ilanga.domain.readings` defines `::reading` next to its query/write fns); only *references* are aggregated into the Malli entity registry in `ilanga.domain.schema`, used by the ADR-019 scope validator and for cross-entity `[:ref]` resolution. The registry is an explicit value threaded via `:registry`, not global state. (ADR-019 already assumes "the entity schema registry"; this ADR fixes where its definitions live and how it is assembled.)

**Hardware descriptors are data, not code** — edn under `resources/hardware/{hardware-id}.edn`, loaded at boot, outside the LLM `:register` scope. There is deliberately no `ilanga.protocol.<model>` namespace; the only protocol code is the generic descriptor-driven decoder (`ilanga.protocol.decode`) plus the algorithm registry it dispatches into. This instantiates ADR-018's "new model = new descriptor, not new code."

## Rationale
- **deps.edn** matches the determinism/auditability ethos and ADR-001's REPL-driven note; declarative `deps.edn` is data, not eval'd code. `tools.build` covers packaging without Leiningen's convention overhead.
- **Nested** makes ADR-004's two layers and ADR-014's determinism boundary *visible* in the namespace tree and recoverable as a directory (`src/ilanga/domain/`), while `:as` aliasing neutralizes the call-site cost — `(readings/latest …)` is identical whether the require reads `ilanga.readings` or `ilanga.domain.readings`. The only real cost is longer require lines, which is minor.
- **Dependency-direction lint** enforces the seam structurally rather than relying on review; the alternative (convention only) is how data/meta drift begins.
- **Co-located schemas** avoid the indirection of a central schema bucket (the entity dependency graph is a DAG — Cycle→Day+Overnight, Reconciliation→BillingCycle, nothing back — so co-location creates no cycles), while the registry gives the validator and cross-entity refs one resolution point.
- **Single product root (`ilanga`) over dual-root (`solar`)** — the domain is product-named rather than kept generic; avoids the thematic redundancy of two "sun" names and avoids a generic-word classpath collision. The benefit of dual-root (a reusable, product-agnostic domain core) is not a near-term goal and does not require a separate root to preserve — the `ilanga.domain.*` subtree remains a clean extraction candidate if that ever matters.
- **Descriptor-as-data** makes "new model = new descriptor, not new code" literally true: adding a model is a data file, zero code.

## Consequences
- (+) The data/meta seam and the determinism boundary are visible in the tree and enforced by lint — the compiler/linter catches a domain namespace drifting into infra.
- (+) The domain is a coherent subtree, testable and (if ever needed) extractable as a unit; infra is flat at the top where it is rarely called from outside.
- (+) Short high-frequency call sites via aliasing; adding a model is a data file.
- (+) Schema changes live next to the code that uses and enforces them.
- (-) A namespace-dependency lint config to maintain; the one genuinely fuzzy classification (engine *orchestration* = `ilanga.engine` vs *pure KPI math* = `ilanga.domain.kpis`) requires discipline — the same discipline ADR-007/010 already demand.
- (-) Two roots to classify; a new namespace must be placed domain-vs-infra (usually obvious).
- (-) A deployable uberjar is platform-specific — DuckDB and SQLite ship native libraries inside their jars (build on the target OS; acceptable for a single-home deployment).

## Alternatives Considered
- **Leiningen** — rejected: more magic, `project.clj` is eval'd code rather than data, heavier; the packaging convenience does not outweigh the determinism/declarative ethos.
- **Polylith** — rejected for now: a tool and mental model for a one-operator, one-app system; the seam is enforced adequately by a lint rule. Revisit if we ever want build-enforced brick boundaries and a multi-base workspace.
- **Single-root flat `ilanga.*`** — rejected: the data/meta seam would be conventional only, with no visible subtree and no structural grouping; relies entirely on the lint rule and naming, with no navigational signal.
- **Dual-root `solar.*` + `ilanga.*`** — rejected: thematically redundant (both mean "sun"), exposes a generic-word classpath collision, and a product-named domain (`ilanga.domain.*`) preserves the extraction option without a second root.