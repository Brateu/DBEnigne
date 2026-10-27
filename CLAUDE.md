# CustomDB — Custom Database Engine in Java

## What this is
A custom database engine built from scratch in Java as a long-term learning and portfolio project. The goal is to understand database internals deeply — storage, indexing, query parsing, transactions, and networking — by building each layer myself.

## Advisory arrangement with Claude
- **Do NOT write code for me.** I am building everything myself.
- Explain concepts, review my code, point out bugs, suggest improvements, explain tradeoffs.
- Provide links to resources when I ask.
- When I share code, review it — don't rewrite it. Suggest what to change and why.
- **Exception — tests:** Claude writes the JUnit tests for each component and explains what each test checks and why (I'm less comfortable with testing). If a test exposes a bug, report it — don't fix the engine code.
- **Exception — tiny typos:** Claude may fix ultra-small typos directly (missing `;`, spacing in messages).

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
**Phase 1 — Storage Engine (page-based).** Resume point as of 2026-10-27.

Done and tested (50 tests pass):
- `db/storage/DiskManager` — FileChannel positional I/O, `checkPageId` helper (DiskManagerTest, 13)
- `db/storage/Frame` — private fields; package-private pin/unpin/markDirty/clearDirty/reset; reset throws if pinned or dirty (FrameTest, 10)
- `db/util/DoublyLinkedList` — my own, null-based, guard in remove, `addLast` returns Node (DoublyLinkedListTest, 14)
- `db/storage/LruReplacer` — list + HashMap; unpin = add candidate, pin = remove candidate, `victim()` → OptionalInt (LruReplacerTest, 13, incl. randomized model test)

**In progress: `db/storage/BufferPool`** (I write it; WIP, doesn't compile yet).
- Fields: DiskManager, `Frame[] frames`, `Map pageTable` (pageId → frameId), `Deque freeList`, LruReplacer.
- Methods: `fetchPage`, `newPage`, `unpinPage(pageId, dirty)`, `flushPage`, `flushAll`, private `findFreeFrame` (free list → victim → write back if dirty → remove old pageId from pageTable), private `frameIdOf`.
- Pitfalls to watch: dirty flag is OR'd (never cleared on unpin); remove the *old pageId* from pageTable on evict; copy into the frame's array; read from disk BEFORE taking a frame (avoid frame leak on IOException); `newPage` gets a frame before `allocatePage`; zero the data in `newPage`.
- Suggested build order: ctor + `frameIdOf` → fetch without eviction → unpin → eviction → newPage → flush.
- Then Claude writes BufferPoolTest (tiny pool, 2–3 frames).

**Next after BufferPool:** slotted heap page.

Already explained (don't re-explain unless asked): pages vs files, slotted pages/fragmentation, whole-page writes, read/write loop, FileChannel.read return values, sync, JUnit basics, model-based testing, LRU in depth, pin count vs replacer pin/unpin, lazy eviction.

## Design decisions
- Page-based storage, `PAGE_SIZE = 4096`.
- Buffer pool with LRU replacement.
- Exception conventions: null → NPE (`Objects.requireNonNull`), bad value → IAE, wrong state → ISE, empty → NoSuchElementException. Check before mutating. Messages include the offending values. Private helpers don't validate.
- Under consideration (not decided): one file per table/index.

## Tech stack
- Language: Java
- Build tool: Gradle (wrapper included; `./gradlew test`)
- No ORM, no external DB libraries — everything from scratch
