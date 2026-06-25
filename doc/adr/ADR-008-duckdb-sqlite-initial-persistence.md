# ADR-008: DuckDB and SQLite JSON as Initial Persistence

## Status
Accepted

## Context
The system has two distinct data concerns with fundamentally different access patterns:

**Time-series / analytics** (readings, days, overnights, cycles, periods):
- Append-only writes
- Time-range queries ("all readings for today")
- Analytical aggregations (max, avg, sum over windows)
- Period rollups (fold days into months)
- Trend queries ("last 30 days of final yield")
- Row-oriented databases perform poorly on these patterns

**Config store** (rules, dashboards, tariffs, permissions, prompts):
- Read-heavy, write-rarely
- Document-like structure (nested EDN maps)
- Needs audit trail (who changed what when)
- LLM writes here — validated before storing
- Small dataset, must be queryable by type and ID

The right database for each concern is different. The system is a home system (single-tenant to start) — no server process to manage is strongly preferred.

## Decision
- **DuckDB** for time-series and analytics data
- **SQLite with JSON columns** for the config store

Both run in-process. No separate database server.

### DuckDB — Time-Series
Columnar, in-process, analytical SQL. Handles the time-series query patterns naturally. Core tables: `readings`, `drafts`, `days`, `overnights`, `cycles`, `periods`. Each time-series row carries `site_id` (the site; ADR-026); `device-serial` on `readings` distinguishes parallel inverters within a site. `tenant_id` is not a local column — it is the file boundary (one file = one tenant; ADR-026).

### SQLite JSON — Config Store
Two tables:

```sql
CREATE TABLE config (
  section     TEXT NOT NULL,
  id          TEXT NOT NULL,
  data        TEXT NOT NULL,        -- JSON/EDN
  version     INTEGER DEFAULT 1,
  created_at  TEXT DEFAULT CURRENT_TIMESTAMP,
  updated_at  TEXT DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (section, id)
);

CREATE TABLE config_history (
  section     TEXT NOT NULL,
  id          TEXT NOT NULL,
  data        TEXT NOT NULL,
  version     INTEGER,
  changed_at  TEXT DEFAULT CURRENT_TIMESTAMP,
  changed_by  TEXT   -- user | llm-mcp | llm-chat | system
);
```

Sections: `rules`, `dashboards`, `tariffs`, `permissions`, `prompts`.

### Clojure Access
Both via `next.jdbc` — same library, different JDBC drivers:
```clojure
(def duckdb-ds (jdbc/get-datasource {:dbtype "duckdb" :dbname "data/solar.ddb"}))
(def sqlite-ds (jdbc/get-datasource {:dbtype "sqlite" :dbname "data/config.db"}))
```

## Alternatives Considered
- **SQLite for both**: adequate for config, poor for analytical time-series queries
- **PostgreSQL for both**: best overall fit, requires running a server — not acceptable for home system initially
- **PostgreSQL + TimescaleDB**: excellent time-series support but server dependency
- **XTDB for config**: Clojure-native, bitemporal, best fit for event-sourced config — but heavyweight for an unproven system
- **DuckDB for both**: columnar is wrong shape for config document storage

## Migration Triggers
| Trigger | From | To |
|---|---|---|
| Config audit trail queries become critical | SQLite JSON | XTDB |
| Multiple concurrent users modifying config | SQLite JSON | PostgreSQL JSONB |
| Cloud deployment needed | Both local | PostgreSQL + TimescaleDB (hypertables space-partitioned by `tenant_id`; Row-Level Security on `tenant_id` replaces local file isolation — ADR-026) |
| DuckDB | Stays — scales comfortably | — |

DuckDB is unlikely to need migration — it scales comfortably into the millions of rows a home system will ever produce. SQLite config is the more likely upgrade candidate.

**Multi-tenant migration (ADR-026):** the per-tenant storage model migrates to cloud as a *contained change* only if two prerequisites hold from day one: (1) all data access is through a `TenantStore` whose clients encapsulate tenant scoping — domain code is blind to `tenant_id` (it filters `site_id`, never `tenant_id`), and the backend is swappable inside `open-store` without touching call sites; and (2) every session/connection carries a `:permission/tenant-id` binding (ADR-013/020) that sets the row-level-isolation variable in cloud. `site_id` is a real local column from day one (the site, domain-filtered, groups parallel inverters); `tenant_id` is the structural isolation key — ADR-026 defers its materialization to cloud: it is stamped from the file identity at export (the filename labels the tenant), transparent to domain code. In cloud, structural isolation between tenants moves from file separation to Postgres Row-Level Security on `tenant_id`. Without these prerequisites, multi-tenant cloud migration is a rewrite, not a migration.

**Cloud-migration mechanism (owns the tech detail ADR-026 defers):** when the cloud trigger fires, this ADR owns the concrete mechanics — Postgres + TimescaleDB hypertables with time partition + space partition on `tenant_id`; `site_id` and `device-serial` travel as ordinary columns; bulk `COPY` load per-tenant to bound batch size; compression policies on old chunks; Row-Level Security policies on `tenant_id` (`SET app.tenant_id = …`; policy `tenant_id = current_setting('app.tenant_id')` on every tenant-scoped table); SQLite config → Postgres JSONB preserving the global/per-tenant split and `config_history`. DuckDB isolates locally by file boundary (no native row-level security, so local multi-tenant is file-per-tenant, not multi-tenant-in-one-file — though a single tenant's file holds multiple sites, distinguished by `site_id`). All of this is behind `open-store` — the `TenantStore` record shape and all domain namespaces are unchanged; only the clients it carries swap.

## Consequences
- (+) Both in-process — no server to manage, install, or backup separately
- (+) DuckDB handles analytical KPI queries efficiently (window functions, columnar scans)
- (+) SQLite provides a simple, reliable config store with a basic audit trail via `config_history`
- (+) Both accessed via `next.jdbc` — swapping either is a contained change behind the persistence layer
- (-) SQLite config audit trail is basic — versioned rows, not full event sourcing. Temporal queries ("what were my rules last Tuesday?") require replaying `config_history`
- (-) DuckDB and SQLite are two dependencies to manage — upgrade cadence must be tracked separately
- (-) Config stored as JSON strings — JSON1 functions needed for querying nested fields
