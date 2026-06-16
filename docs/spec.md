# Ember — Build Specification & Plan (CodeCrafters-aligned)

An in-memory key-value store built from scratch in Java — a mini Redis — built stage-by-stage against the CodeCrafters "Build Your Own Redis" test harness, with the from-scratch depth that makes it a portfolio piece rather than a finished tutorial.

> **Working name:** Ember (in-memory, fast, volatile — rename freely).
> **The harness:** CodeCrafters "Build Your Own Redis" — test-driven, git-push-to-pass, Java starter repo. Each push runs your program against a real Redis client in their CI. The live course UI is the source of truth for the exact current stage list; the stages below are faithful to the course as of early 2026 but it adds/reorders over time.
> **The target architecture:** a single-threaded, NIO-event-loop server speaking RESP, with the core data types, lazy + active expiry, memory-bounded eviction, RDB + AOF persistence, and master/replica replication.
> **Why it's FAANG-internship-strong:** CodeCrafters gives breadth validated by tests (incl. replication and streams); the Ember depth (hand-written event loop, approximate LRU, AOF, a skip list) gives the layer beneath every framework you've used. You validate against the real `redis-cli` and benchmark with the real `redis-benchmark`.

---

## 1. What you're building (one paragraph)

A TCP server that multiplexes many client connections on one thread via a Java NIO `Selector`, parses the Redis wire protocol (RESP) incrementally as bytes arrive, executes commands against an in-memory keyspace, and writes RESP replies back. On top of the keyspace sit the Redis data types (strings, lists, hashes, sets, sorted sets), per-key TTLs with lazy + active expiration, a `maxmemory` cap with sampling-based eviction, two persistence paths (read RDB files + write an append-only command log and snapshots), and **master/replica replication** (handshake, command propagation, `WAIT`/`ACK`). The headline design fact you'll defend in interviews: command execution is **single-threaded on purpose** — with the data structures touched by exactly one thread there are no locks and no races, and the bottleneck is network and memory, not CPU contention.

---

## 2. Requirements

### Functional
- Speak RESP over TCP; interoperate with the real `redis-cli`.
- Core commands: `PING`, `ECHO`, `GET`/`SET`/`DEL`/`EXISTS`, `INCR`/`DECR`, `EXPIRE`/`TTL`/`PERSIST`, `TYPE`, `CONFIG GET`.
- The five core data types: string, list, hash, set, sorted set (with range queries).
- Per-key expiration: lazy (on access) + active (background sampling).
- `MULTI`/`EXEC`/`DISCARD` transactions.
- **Replication:** replica↔master handshake, write propagation to replicas, `REPLCONF ACK`/`GETACK` offset tracking, `WAIT`.
- **Streams:** `XADD`/`XRANGE`/`XREAD` incl. blocking reads.
- Persistence: **read** the RDB binary format; **write** an append-only command log (fsync policies + rewrite) and RDB snapshots.
- `maxmemory` cap with an eviction policy (approximate LRU / LFU / random / volatile-ttl).
- Pipelining (many commands batched in one buffer).

### Non-functional
- **Single-threaded command execution** — one event loop owns the keyspace; concurrency lives only at the I/O edges.
- **Incremental parsing** — a command split across reads, or several in one read, both handled. Never assume one read == one command.
- **Bounded latency under load** — a slow client must not stall the loop (buffer writes, never block).
- **Exact replication offset accounting** — bytes counted precisely for `ACK`/`WAIT` to work.
- **Crash recovery** — rebuild state from AOF and/or the latest snapshot on boot.

### Constraints / assumptions
- Java 25, the CodeCrafters Java starter (`Main.java` + `your_program.sh`), Gradle/Maven per the starter. Solo, evenings + weekends.
- Write the event loop yourself with NIO — pulling in Netty defeats the purpose (Netty is a fine *stretch* comparison).
- Progress is gated by passing CodeCrafters stages; depth items are added between/after tracks.

---

## 3. High-level architecture

```
   Many TCP clients ─────────────┐                         replication link (RESP command stream)
                                  ▼                         ┌──────────────────────────────────────┐
                      ┌──────────────────────────┐         │                                        │
                      │   NIO Selector (1 thread) │         ▼                                        │
                      │   the event loop          │   ┌───────────────┐  propagated writes           │
                      └────────────┬─────────────┘   │  REPLICA node │◀──────────────────────────────┘
                                   │ bytes in         │  (same engine,│
                                   ▼                  │   read path)  │   sends REPLCONF ACK <offset>
                      ┌──────────────────────────┐    └───────────────┘
                      │  RESP parser (incremental)│   buffers partial reads; emits whole commands
                      └────────────┬─────────────┘
                                   │ Command objects
                                   ▼
                      ┌──────────────────────────┐
                      │   Command dispatcher       │   pattern-match on command type
                      └────────────┬─────────────┘
                                   │ mutate / read              (writes also → AOF + propagate to replicas)
                                   ▼
        ┌──────────────────────────────────────────────────────────────┐
        │                    Keyspace (one HashMap)                       │
        │   key → RedisObject (String | List | Hash | Set | SortedSet)    │
        │   + expires map (key → expiry timestamp)                        │
        └───────┬───────────────────────────────┬────────────────────────┘
                │ active expiry (sampled)        │ eviction (sampled LRU/LFU when over maxmemory)
                ▼                                 ▼
   ┌──────────────────────┐          ┌──────────────────────────────────────┐
   │  background timer tick│          │  persistence                          │
   │  (on the loop)        │          │  RDB read (load on boot) + RDB snapshot│
   │                       │          │  + AOF write (fsync: always/everysec/no)│
   └──────────────────────┘          └──────────────────────────────────────┘
```

---

## 4. Tech stack & why

| Concern | Choice | Why this, not the alternative |
|---|---|---|
| Harness | CodeCrafters test suite, git-push-to-pass | Test-validated breadth; the stages enforce real-client correctness incl. replication/streams. |
| Concurrency (target) | Single-threaded NIO `Selector` event loop, hand-written | The Ember thesis — reactor pattern, `epoll`-style readiness, no lock contention. (See §5 for the start-simple-then-refactor path.) |
| Protocol | Custom RESP2 codec (RESP3 stretch) | Simple enough to implement; the incremental-parsing problem is the lesson. |
| Keyspace | One `HashMap<String, RedisObject>` + an `expires` map | Single-threaded, so no concurrent map — that's the design payoff. |
| Sorted set | **Hand-written skip list** + hash map | Mirrors real Redis; CodeCrafters tests behavior, so the skip list is your depth choice over a `TreeMap`. |
| Eviction | Sampling-based approximate LRU/LFU | Real Redis samples rather than maintaining an exact LRU list — cheaper, a good talking point. |
| Persistence | RDB **read** (CC) + AOF **write** + RDB snapshot (Ember) | Covers both directions and both formats; teaches the durability/perf trade-off. |
| Validation | `redis-cli` + `redis-benchmark` | If the real client/benchmark work against your server, that's unfakeable proof. |
| Build / bench | Gradle or Maven (starter's choice), JUnit, JMH | Standard. JMH for parser/data-structure micro-benchmarks. |

---

## 5. Core design ideas (the "how" behind the stages)

**The single-threaded event loop (the Ember thesis).** One thread runs `selector.select()`, then for each ready channel: accept connections, read bytes into a per-connection buffer, feed them to that connection's parser, execute complete commands, and queue replies to the connection's write buffer (flush what the socket takes; register `OP_WRITE` for the rest). The keyspace is touched by exactly one thread → no locks, no races; CPU is rarely the bottleneck. Be ready to explain Redis 6's refinement: separate **I/O threads** for parsing/socket I/O while keeping *execution* single-threaded.

> **Practical sequencing note (honest):** CodeCrafters' "handle concurrent clients" stage passes fine with thread-per-client or a virtual-thread-per-task executor, and fighting the `Selector` while *also* learning the protocol is painful. Recommended path: **pass the base stages with virtual-thread-per-client, then refactor the foundation to the single-threaded NIO loop as the first depth item** (Track F). The NIO loop is the canonical end-state architecture — the thing that makes it "Ember" — but you don't have to reach it on stage 1. If you want the harder run, commit to NIO from the start.

**The incremental RESP parser (the partial-read trap).** TCP is a byte stream, not messages: one `read()` may hold half a command, one-and-a-half, or fifty pipelined ones. The parser must be a resumable state machine over a growing buffer — try to parse a full RESP array from a marked position; if you run out of bytes mid-element, reset to the mark and wait for more. This bites hardest in replication, where the master streams a never-ending command flow and the `ACK` offset must count bytes exactly. Reuse the same parser everywhere.

**Expiration — two mechanisms.** *Lazy:* on access, if expired, delete and treat as missing (cheap, but untouched dead keys linger). *Active:* a periodic tick samples N random keys from the `expires` map, deletes expired ones, repeats if the expired fraction is high (Redis's probabilistic sweep). CodeCrafters covers lazy; active is Ember depth (Track F).

**Eviction.** When memory exceeds `maxmemory`: sample K keys, evict per policy — approximate **LRU** (a coarse per-object clock, not an exact list), **LFU** (decaying logarithmic counter), `random`, or `volatile-ttl`. The "why approximate, not exact" answer: an exact LRU list costs memory and pointer churn per access for marginal hit-rate gain. Not in CodeCrafters — Ember depth (Track F).

**Persistence — both directions.** *RDB read* (CodeCrafters): parse the binary format — header, length encoding (the 6/14/32/64-bit scheme + special integer encodings), opcodes (`SELECTDB`, expiry), type bytes — and load keys on boot. *AOF write* (Ember): log write commands as RESP; replay on restart; fsync policy is the knob (`always`/`everysec`/`no`); **AOF rewrite** compacts to the minimal command set. *RDB snapshot write* (Ember): serialize the keyspace point-in-time. Note: **Java has no `fork()`**, so snapshotting can't mirror C Redis's copy-on-write child — choose pause-and-dump (simple, brief block) or a copy-on-write view (harder, non-blocking) and justify it.

**Replication.** Replica sends `PING` → `REPLCONF listening-port`/`capa` → `PSYNC ? -1`; master replies `+FULLRESYNC <replid> 0` then ships an (empty, for the challenge) RDB; thereafter the master propagates every write down the link, the replica applies them, and `REPLCONF GETACK`/`ACK <offset>` plus `WAIT` provide acknowledgement and write-durability counting. This was an Ember *stretch* goal; CodeCrafters makes it a guided core track — take advantage.

---

## 6. The merged build plan

Tracks tagged **[CC]** are driven by CodeCrafters stages; **[Ember]** are depth items the course doesn't cover. Each track is independently demoable. Stage names follow the course as of early 2026 — verify against the live UI.

### Track 0 — Foundation & base commands **[CC]**
Stages: bind to a port → respond to PING → multiple PINGs → handle concurrent clients → `ECHO` → `SET`/`GET` → `Expiry` (passive `PX`).
Build on the resumable RESP parser from the start (§5). Concurrency: virtual-thread-per-client is fine here; NIO refactor is Track F.
**Acceptance:** `redis-cli` does `PING`/`ECHO`/`SET`/`GET`; a key with `PX` expires; a command split across two packets parses correctly.

### Track A — Transactions **[CC]**
Stages: `INCR` (several) → `MULTI` → `EXEC` → empty transaction → queueing → executing → `DISCARD` → failures within a transaction → multiple transactions.
**Acceptance:** a `MULTI … EXEC` block applies atomically within the single-threaded loop; `DISCARD` drops the queue.

### Track B — Replication **[CC]** (high value — do it while the codebase is small)
Stages: `--replicaof` config → `INFO replication` (master, then replica) → replication id/offset → replica handshake (`PING` → `REPLCONF` → `PSYNC`) → master handshake handling → empty RDB transfer → single- then multi-replica propagation → replica command processing → `REPLCONF ACK`/`GETACK` → `WAIT`.
**Acceptance:** writes on the master appear on replicas; `WAIT` returns the count of replicas that acked an offset.

### Track C — Persistence **[CC read + Ember write]**
Stages **[CC]**: RDB config (`CONFIG GET dir`/`dbfilename`) → read a key → read a string value → multiple keys → multiple values → values with expiry (parse the RDB format, load on boot).
Add **[Ember]**: AOF write (`always`/`everysec`/`no` + rewrite); RDB snapshot write; recovery prefers AOF then snapshot.
**Acceptance:** boots and loads an existing RDB; after writes, kill and restart → state restored from AOF/snapshot.

### Track D — Aggregate data types **[CC behavior + Ember internals]**
Stages **[CC]**: Lists (`RPUSH`/`LPUSH`/`LRANGE` incl. negative indexes/`LLEN`/`LPOP` with count/`BLPOP` with timeout); Sorted Sets (`ZADD`/`ZSCORE`/`ZRANK`/`ZRANGE`/`ZRANGEBYSCORE`/`ZCARD`/`ZREM`); Hashes/Sets as needed.
**[Ember]**: back the sorted set with a **hand-written skip list** + member→score hash (not a `TreeMap`), so "skip list vs balanced tree" is something you built.
**Acceptance:** `ZRANGE`/`ZRANGEBYSCORE` return correct order; `BLPOP` parks the client and wakes on push without blocking the loop.

### Track E — Streams & Pub/Sub **[CC]**
Stages: Streams (`TYPE` → `XADD` with ID validation and auto-generation → `XRANGE` incl. `-`/`+` → `XREAD` single/multiple → blocking `XREAD` with/without timeout and using `$`); Pub/Sub (`SUBSCRIBE`/`PUBLISH`/`UNSUBSCRIBE`).
**Acceptance:** blocking `XREAD`/`SUBSCRIBE` integrate with the same client-parking mechanism as `BLPOP`.

### Track F — Depth pass **[Ember]** (the FAANG differentiator)
- **Refactor to the single-threaded NIO event loop** (if you started with threads) — `Selector`, `OP_READ`/`OP_WRITE`, `ByteBuffer` flip/compact, slow-client backpressure.
- **`maxmemory` + eviction** — sampled approximate LRU/LFU.
- **Active expiration** — the periodic sampled sweep on top of lazy expiry.
- **Redis-6-style I/O threads** (optional) — multi-thread parsing/socket I/O, keep execution single-threaded; benchmark the gain.
**Acceptance:** the server runs on one execution thread; memory stays bounded past `maxmemory`; untouched expired keys get swept; a slow client doesn't stall others.

### Track G — Hardening + benchmarks **[Ember]**
`redis-benchmark` runs; README with the architecture, design decisions, and reproducible throughput/latency numbers.
**Acceptance:** a stranger can clone, run, and understand it from the README; ops/sec figures are reproducible.

---

## 7. Suggested order & checkpoints

1. **Track 0** — working `SET`/`GET`/expiry server (real mini-Redis).
2. **Track A (Transactions)** — small, reinforces command dispatch.
3. **Track B (Replication)** — highest-value; do it while the codebase is small.
4. **Track C (Persistence)** — RDB parsing pairs naturally with replication's RDB transfer; add AOF.
5. **Track D (Data types)** — with the hand-written skip list.
6. **Track E (Streams/Pub-Sub)** — breadth.
7. **Track F (Depth pass)** — NIO loop, eviction, active expiry, I/O threads.
8. **Track G** — benchmarks + README.

Stop-anywhere checkpoints: after Track 0 you have a real mini-Redis; after Track B it replicates; after Track F it's the FAANG-distinctive version.

---

## 8. Risks & gotchas

- **Partial reads (§5)** — the most common bug. Resumable parser from day one; it's non-negotiable once you reach replication.
- **Replication offset accounting** — `ACK`/`WAIT` break if you miscount stream bytes; count exactly what crosses the link.
- **Slow-client backpressure** — buffer outgoing replies and toggle `OP_WRITE`; never block the loop to flush.
- **No `fork()` for snapshots** — Java forces a different RDB-write strategy than C Redis; decide and justify (§5).
- **Blocking commands** (`BLPOP`, blocking `XREAD`, `SUBSCRIBE`) on a single-threaded loop — you can't actually block; park the client and wake it when data arrives. Design the parking mechanism once and reuse it.
- **RDB format bugs** — a wrong length-encoding or opcode silently misreads keys; test against a known RDB file.
- **Memory accounting is approximate** — JVM per-object byte estimates are fuzzy; be honest that the cap is heuristic.

---

## 9. Interview talking points to mine

- **Why single-threaded execution** — no lock contention, simplicity, CPU isn't the bottleneck; then the Redis-6 I/O-threads refinement.
- **The C10k problem and the reactor pattern** — readiness selection (`epoll`/`Selector`) vs thread-per-connection.
- **Incremental parsing** — TCP is a byte stream; how your resumable parser handles fragmentation, pipelining, and the replication stream.
- **Replication** — the `PSYNC` handshake, `FULLRESYNC` + RDB transfer, write propagation, and `WAIT`/`ACK` offset semantics.
- **Approximate vs true LRU** — why sampling beats an exact list.
- **Lazy vs active expiration** — the two-mechanism design.
- **AOF vs RDB** — durability/performance trade-off, the fsync knob, and the `fork()`-in-Java constraint.
- **Skip list vs balanced tree for ZSET** — why Redis chose skip lists (simplicity, range scans, concurrency-friendliness) — and you built one.

---

## 10. Stretch goals (don't block the plan)

- **Redis-6 I/O threads** (if not done in Track F) — benchmark the gain.
- **Cluster mode** — hash-slot partitioning across nodes.
- **RESP3** (via `HELLO`), **keyspace notifications**, a **block cache** for large values.
- **Diskless replication** and partial resync (`PSYNC` with a backlog).

---

## 11. What you'd revisit as it grows

- One execution thread caps throughput at one core → I/O threads, then sharding.
- Single instance → replication (built) for HA/read scaling; then cluster-mode hash slots for horizontal capacity.
- Approximate memory accounting → an allocator-aware accounting layer.
- Full-resync-only replication → partial resync with a replication backlog to survive brief disconnects.

State the limits proactively — knowing exactly why the single thread is both the strength and the ceiling is the signal.

---

## 12. One honest note on the learning

CodeCrafters is a *do-it-yourself* challenge — the value is writing the code and passing the tests yourself. I'll help you plan, explain a protocol detail (RESP framing, the `PSYNC` handshake, RDB opcodes), debug a stage you're stuck on, or design the Ember depth items — leaning toward unblock-and-explain rather than handing over whole stage solutions, so the practice stays yours.