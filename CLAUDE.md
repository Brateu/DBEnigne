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
Postgres-style throughout. Primary reference: Petrov, *Database Internals* (ch. 3 file formats, ch. 2/4 B-trees, ch. 5 buffer/WAL/concurrency, ch. 6–7 variants, ch. 8–14 distributed). Gaps filled by CMU 15-445 (execution), Postgres docs/source, Hellerstein's *Architecture of a Database System*.

**Workflow for every major choice:** discuss pros/cons → I pick → I implement → tag. Alternatives live as **interface + implementations** only where Postgres has a pluggable point or we'll benchmark both (Replacer, table AM, index AM, concurrency control). **Git tags** for milestones, **branches** (`exp/...`) for throwaway experiments.

### Phase 1 — Storage → tag `v0.1-storage`
1. `HeapPage` — Postgres layout: header (`lsn`, `checksum`, `flags`, `lower`, `upper`, `special`, version), line pointers (offset/len/flags: UNUSED/NORMAL/DEAD; REDIRECT later for HOT), `isNew()` + init
2. Page checksum — CRC32C computed on write-out, verified on read → `StorageException`
3. Page guards — AutoCloseable read/write guards (auto-unpin; latches added in 4.7)
4. Tuple format — header with reserved `xmin`/`xmax`/`ctid`/infomask/null bitmap; types INT, BIGINT, BOOLEAN, VARCHAR/TEXT, NULL; `Schema` + serializer
5. `HeapFile` — insert/get/update/delete by `RecordId`, seq-scan iterator, naive free-space search (FSM later)
6. Minimal catalog — table name → fileId + schema, persisted in a catalog file
7. (Variant) CLOCK replacer, benchmark vs LRU

### Phase 2 — Indexing (Postgres nbtree) → tag `v0.2-index`
1. B+tree page layouts — internal/leaf, right-sibling link + high key (Lehman–Yao), typed key comparison
2. Search, insert with splits up to root, range-scan iterator over sibling links
3. Delete = mark dead, no eager merge (page deletion in VACUUM)
4. Index AM interface, `CREATE INDEX`, unique constraints, maintenance on heap insert/update/delete
5. (Variants) hash index; LSM tree on a branch (book ch. 7)

### Phase 3 — SQL (parser → analyzer → planner → executor) → tag `v0.3-sql`
1. Lexer + parser → AST: CREATE/DROP TABLE, CREATE INDEX, INSERT, SELECT (WHERE/ORDER BY/LIMIT), UPDATE, DELETE
2. Analyzer — resolve names against catalog, type-check
3. Executor — Volcano iterators: SeqScan, IndexScan, Filter, Projection, Insert/Update/Delete, Sort, Limit, NestedLoopJoin, HashJoin, Aggregation/GROUP BY
4. Rule-based planner (indexed predicate → IndexScan)
5. Local REPL

### Phase 4 — Transactions & concurrency → tag `v0.4-txn`
1. WAL manager — append, assign LSNs, `flushUpTo(lsn)`, hooked into buffer pool write-back (WAL rule)
2. Redo-only crash recovery (`record.lsn > page.lsn`), full-page images after checkpoint
3. Checkpoints + log truncation
4. Transaction manager — xids, BEGIN/COMMIT/ABORT, commit log (CLOG)
5. MVCC visibility — snapshots, xmin/xmax rules; Read Committed + Repeatable Read
6. Row locks (via xmax), lock manager, deadlock detection (wait-for graph)
7. Thread safety — buffer pool latch, page latches in guards, Lehman–Yao concurrent B-tree
8. VACUUM — dead tuples + their index entries
9. (Variants) strict 2PL behind a concurrency-control interface; SSI stretch

### Phase 5 — Network → tag `v0.5-server`
1. TCP server, thread per connection, per-session transaction state
2. Own framed protocol first, then a subset of the real Postgres wire protocol v3 (so `psql`/JDBC can connect)
3. Small CLI client

### Phase 6 — Extras
- Auth (password → SCRAM), GRANT/REVOKE
- Cost-based optimizer: ANALYZE stats (histograms, n_distinct), join ordering
- Prepared statements (extended query protocol)
- Streaming replication (ship WAL to a standby); optionally Raft (book ch. 14)
- Deferred scalability: FSM/VM forks, TOAST, 1 GB segments, catalog as tables, HOT updates, index-organized table AM

## Current phase
**Phase 1 — Storage Engine (page-based).** Resume point as of 2026-10-27.

Done and tested (100 tests pass):
- `db/storage/DiskManager` — FileChannel positional I/O, `checkPageId` helper (DiskManagerTest, 13)
- `db/storage/Frame` — private fields; package-private pin/unpin/markDirty/clearDirty/reset; reset throws if pinned or dirty (FrameTest, 10)
- `db/util/DoublyLinkedList` — my own, null-based, guard in remove, `addLast` returns Node (DoublyLinkedListTest, 14)
- `db/storage/LruReplacer` — implements `Replacer` interface; list + HashMap; `markEvictable` = add candidate, `markNonEvictable` = remove candidate, `victim()` → OptionalInt (LruReplacerTest, 13, incl. randomized model test)

- `db/storage/BufferPool` — fields `pageIdToFrameId`, `freeList`, `Replacer replacer`; private `acquireFrame` (free list → victim → write back if dirty + clearDirty → remove old pageId) and `frameIdOf`; `fetchPage` reads disk before acquiring a frame; `newPage` acquires frame before `allocatePage` and pushes the frame back on failure; `unpinPage` ORs dirty, marks evictable at pin count 0; `flushPage`/`flushAll` (iterates map keys); I/O wrapped in `StorageException` (BufferPoolTest, 28, incl. closed-disk failure tests)
- `DiskManager` caches `numPages` (updated in `allocatePage` after the write succeeds)
- `Frame.zeroData()` added (no dedicated FrameTest yet; covered via BufferPoolTest)

- **PageId refactor done:** `PageId(fileId, pageNo)` record (validates ≥ 0; PageIdTest, 6); `StorageManager` (`open` creates/returns the one instance per fileId, `get` throws IAE if not open, `close` closes all with `addSuppressed`; StorageManagerTest, 9); `Frame` holds `PageId` (`null` = empty, `reset` rejects null; FrameTest, 12); `BufferPool` takes a `StorageManager`, keyed by `PageId`, `newPage(fileId)` looks up the file before `acquireFrame` (BufferPoolTest, 33, incl. two-file tests).
- Note for later: when a single file can be dropped/closed, evict its pages from the pool first (writeBack on a closed file throws IAE, which `acquireFrame` doesn't catch).

- **Generic slotted page done (173 tests pass):** `db/storage/page/Page` — stateless view over the frame's `byte[]` (one `ByteBuffer` field, absolute get/put, big-endian, unsigned 16-bit reads). 24-byte header (lsn 0/8B, checksum 8/4B, flags 12, lower 14, upper 16, special 18, version 20, reserved 22). Line pointers 4B bit-packed (offset bits 0–14, state 15–16, length 17–31). `LinePointerState` enum (UNUSED 0, NORMAL 1, REDIRECT 2, DEAD 3; `fromCode`). API: `isNew` (upper == 0), `init(specialSize)` (zeroes + formats; max special = PAGE_SIZE − 24 − 4), header getters, `setLsn`/`setChecksum` public, flags protected, `slotCount`, `freeSpace` (upper − lower), `maxItemSize`, `canFit(length)` (fast path on freeSpace, else private `reclaimableSpace` = special − lower − live bytes), `insert` (auto-compacts after all checks), `get` (copy), `stateOf`, `delete` (→ DEAD, keeps offset/len, slots never reused in Phase 1), `update` (shrink in place; grow: check `reclaimable + oldLen`, mark DEAD, compact if needed, place below upper, same slot), package-private `compact`. Private `checkSlot`, `readLinePointer`/`writeLinePointer` + `LinePointer` record. (PageTest 70, LinePointerStateTest 3)
- IAE vs ISE rule of thumb: would the same call succeed on a different/emptier page? yes → ISE (page state), no → IAE (bad argument).

- Header validation done: constructor runs private `validateHeader()` for non-new pages (`24 ≤ lower ≤ upper ≤ special ≤ PAGE_SIZE`, `(lower − 24) % 4 == 0`, version == 1) → `CorruptPageException extends StorageException` (message-only constructor added to `StorageException`). 184 tests pass (PageTest 81).

- `HeapPage extends Page` done: public constructor (`super(data)`, then non-new page with special != PAGE_SIZE → `CorruptPageException`), `init()` → `init(0)`; `Page.init(int)` is now `protected`. 195 tests pass (HeapPageTest 11).

**Now:** Phase 1.2 — page checksum. Decisions made (all as recommended):
- CRC32C (`java.util.zip.CRC32C`), 32 bits stored in header bytes 8–11. Covers bytes `[0, 8)` + `[12, PAGE_SIZE)` (skips its own field, no mutation) **plus the pageNo** (catches misdirected writes; fileId not included).
- Done in `BufferPool`, not `DiskManager` or the `Page` constructor. Always on, no flag. Stamped in place in the frame (revisit when latches arrive in 4.7).
- All-zero pages (from `allocatePage`) are accepted; the zero scan runs only after a mismatch.
- API, step 1 (I implement next) — static helpers on `Page` over a raw `byte[]`: `computeChecksum(byte[] data, int pageNo)` → int, `stampChecksum(byte[] data, int pageNo)` (writes bytes 8–11, big-endian), `verifyChecksum(byte[] data, int pageNo)` → boolean (match, or all zeros via private `isAllZeros`). Traps: `CRC32C.update(int)` uses only the low 8 bits — feed pageNo as 4 bytes; `getValue()` is a long → cast to int.
- Step 2 — wire into `BufferPool`: `writeBack` stamps right before `write` (inside `if dirty`; covers eviction/flushPage/flushAll); `fetchPage` verifies right after `read`, **before** `acquireFrame` → `CorruptPageException("checksum mismatch reading page " + pageId)`; `newPage` unchanged.
- Claude then: tests for the helpers (determinism, single-bit flip, pageNo 7 vs 263, bytes 8–11 ignored, all-zero accepted) and BufferPool (round trip, on-disk corruption → exception with no frame leaked, page copied to wrong position → mismatch); update the ~10 existing BufferPoolTests that compare raw disk bytes or write raw patterns to disk.

After that: page guards, tuple format, `HeapFile`.

Already explained (don't re-explain unless asked): pages vs files, slotted pages/fragmentation, whole-page writes, read/write loop, FileChannel.read return values, sync, JUnit basics, model-based testing, LRU in depth, pin count vs replacer pin/unpin, lazy eviction, heap vs index-organized storage, WAL (steal/no-force, redo-only thanks to MVCC, checkpoints, full-page images), LSN/pageLSN, Postgres-style MVCC (xmin/xmax/ctid, snapshots, CLOG, VACUUM), page layout (header/line pointers/items/special space), line-pointer bit packing with shifts and masks, reclaimable vs free space, the canFit fast path, update grow/shrink, why HeapPage is thin, checksums (what CRC32C is, why 32 bits, skipping its own field, mixing in pageNo).

## Design decisions
- Page-based storage, `PAGE_SIZE = 4096`.
- Buffer pool with LRU replacement.
- Exception conventions: null → NPE (`Objects.requireNonNull`), bad value → IAE, wrong state → ISE, empty → NoSuchElementException, I/O failure → `StorageException` (unchecked, wraps the cause when there is one; detected on-disk corruption → `CorruptPageException`; `DiskManager` itself keeps checked `IOException`, `BufferPool` translates). Check before mutating. Messages include the offending values. Private helpers don't validate.
- Failure paths undo reservations: a method that takes a frame out of the pool's bookkeeping and then fails must put it back (`newPage` → `freeList.push`, `acquireFrame` write-back failure → `markEvictable`).
- **One file per table/index, Postgres-style.** Buffer pool keyed by `PageId(fileId, pageNo)` (like Postgres BufferTag); a storage manager maps `fileId → DiskManager` (like smgr); `DiskManager` stays one-file. Heap page mimics Postgres layout (`lower`/`upper`, line pointers, `lsn` in header, `isNew()` for zeroed pages). `RecordId = (pageNo, slotId)` within a table (like ctid).
- **Heap storage, not index-organized:** rows in heap files, every index (incl. PK) a separate B+tree → `RecordId`. Each phase works without the next; an index-organized variant can come later as a second table AM.
- **Postgres-style MVCC + redo-only WAL:** versions live in the heap (xmin/xmax/ctid), abort = flip CLOG status, so recovery needs no undo. Buffer pool is steal/no-force; write-back must call `wal.flushUpTo(pageLsn)` once WAL exists.
- **Deferred until the whole engine works (scalability upgrades):** 1 GB segment files, forks (free space map / visibility map), TOAST, catalog stored as tables (pg_class-style). MVCC tuple headers come in Phase 4 — leave room in the tuple layout.

## Tech stack
- Language: Java
- Build tool: Gradle (wrapper included; `./gradlew test`)
- No ORM, no external DB libraries — everything from scratch
