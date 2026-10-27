# CustomDB — Custom Database Engine in Java

## What this is
A custom database engine built from scratch in Java as a long-term learning and portfolio project. The goal is to understand database internals deeply — storage, indexing, query parsing, transactions, and networking — by building each layer myself.

## Advisory arrangement with Claude
- **Do NOT write code for me.** I am building everything myself.
- Explain concepts, review my code, point out bugs, suggest improvements, explain tradeoffs.
- Provide links to resources when I ask.
- When I share code, review it — don't rewrite it. Suggest what to change and why.

## My background
- Strong Java developer — this is my primary language.
- Built a MicroJava compiler (lexer, parser, code generation) so I'm comfortable with parsing, ASTs, grammars.
- University coursework in concurrent/distributed programming (C-Linda, CSP, monitors, rendezvous, Ada).
- University coursework in software security (static analysis, pentesting, CSRF/XSS/SQLi).
- Backend engineering focus.

## Architecture roadmap

### Phase 1 — Storage Engine
- Log-structured or page-based storage
- Basic operations: get, put, delete
- Disk persistence (not just in-memory)
- Serialization of records to bytes

### Phase 2 — Indexing
- B-tree or LSM tree implementation
- Eliminate full-scan lookups
- Index maintenance on insert/update/delete

### Phase 3 — SQL-like Query Parser
- Lexer + parser for basic SQL (SELECT, INSERT, UPDATE, DELETE)
- AST representation of queries
- Query execution against the storage engine
- Leverage compiler-building experience from MicroJava

### Phase 4 — Transactions & Concurrency Control
- Write-ahead logging (WAL)
- MVCC or lock-based concurrency control
- ACID guarantees
- Crash recovery from WAL

### Phase 5 — Wire Protocol
- TCP server so external clients can connect
- Simple text-based or binary protocol
- Connection handling with threads
- Basic client library or CLI tool

### Phase 6 — Extras (stretch goals)
- Authentication and basic permissions
- Query planner / optimizer
- Replication
- Prepared statements

## Current phase
Not started yet — setting up the project.

## Design decisions
(Update this as decisions are made)

## Tech stack
- Language: Java
- Build tool: (TBD — Maven or Gradle)
- No ORM, no external DB libraries — everything from scratch
