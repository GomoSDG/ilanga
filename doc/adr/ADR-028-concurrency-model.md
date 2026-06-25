# ADR-028: Concurrency Model

## Status
Proposed

## Problem
The application has multiple concurrent concerns running simultaneously:
- Aleph/Netty manages TCP connections on its own thread pool
- core.async channels buffer the ingestion→engine handoff
- DuckDB writes are blocking JDBC calls
- The engine pipeline processes signals, potentially long-running
- A `:tick` timer fires periodically
- LLM calls (MCP/chat) may be async or blocking

How these interact — what runs on what thread, how blocking I/O is handled, how go blocks and threads are used, and how backpressure propagates — has not been stated. Each subsystem has made local decisions (Aleph for TCP, core.async for handoff) but the full model has not been composed.

## Why this must be decided
- **Blocking in go blocks causes thread starvation** — core.async go blocks run on a fixed-size thread pool; a blocking JDBC call inside a go block can exhaust it, freezing the entire application.
- **Aleph/Netty threads must not be blocked** — Netty's I/O threads are precious; any blocking operation on an Aleph callback causes dropped connections under load.
- **DuckDB JDBC is blocking** — writes and reads block the calling thread; these cannot safely run on Netty or go-block threads.
- **Backpressure policy is undecided** — what happens when the core.async ingestion channel is full (drop oldest, block, park the connection) is an explicit open item; the concurrency model is the right place to decide it.
- **The `:tick` interval is undecided** — the timer frequency affects what threads it touches and how it interacts with the pipeline.
- **Wrong model causes subtle bugs under load** — these failures only appear at production throughput, not in development.

## Concerns to address
- Which operations are safe on Netty threads (fast, non-blocking only)?
- Which operations go on `core.async/thread` (blocking allowed) vs `go` blocks (non-blocking only)?
- How are DuckDB writes dispatched — `core.async/thread`? A dedicated write thread? A thread pool?
- What is the backpressure policy on the ingestion→engine channel?
- What drives the `:tick` signal and on what thread?
- How are LLM calls dispatched — blocking thread? async HTTP?
- **Per-connection `open-store` construction** (ADR-020/026) — `open-store` runs on the thread that accepted the connection (a Netty I/O thread, per ADR-018). Opening a DuckDB datasource is a blocking JDBC call and must not run on the Netty thread; either the construction is offloaded to `core.async/thread` before the stream enters normal processing, or the datasource is pre-opened (pooled) so `open-store` only assembles a cheap binding over it. The decision here determines which.

## Decision
TODO
