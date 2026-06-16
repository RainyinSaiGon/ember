# Ember

An in-memory key-value store built from scratch in Java — a mini Redis — validated stage-by-stage against the [CodeCrafters "Build Your Own Redis"](https://codecrafters.io/challenges/redis) test harness.

## What it is

A TCP server that speaks the Redis wire protocol (RESP), validated against the real `redis-cli` and `redis-benchmark`. The headline design decision: **command execution is single-threaded on purpose** — with the keyspace touched by exactly one thread there are no locks and no races, and the bottleneck is network and memory, not CPU contention.

## Documentation

| Doc | Contents |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | Layer diagram, package map, key invariant |
| [`docs/design-decisions.md`](docs/design-decisions.md) | Rationale for single-thread, RESP parser, skip list, LRU, expiry, persistence |
| [`docs/spec.md`](docs/spec.md) | Full build plan, track acceptance criteria, interview talking points |

## Package layout

```
src/main/java/com/authlyn/
├── Main.java             entry point
├── server/               NIO Selector, connection lifecycle
├── protocol/             RESP parser (incremental) + encoder
├── command/              dispatcher + per-command handlers
├── store/                Keyspace, RedisObject, expires map
├── persistence/          RDB reader, AOF writer
└── replication/          master/replica handshake, ACK/WAIT
```

## Build & test

```bash
./gradlew build
./gradlew test
```

Once the server is running, connect with the real Redis client:

```bash
redis-cli PING
redis-cli SET foo bar
redis-cli GET foo
```

## Feature tracks

| Track | Features | Status |
|---|---|---|
| 0 — Foundation | `PING`, `ECHO`, `SET`/`GET`, passive expiry (`PX`), concurrent clients | |
| A — Transactions | `INCR`/`DECR`, `MULTI`/`EXEC`/`DISCARD` | |
| B — Replication | `--replicaof`, `PSYNC` handshake, write propagation, `REPLCONF ACK`/`WAIT` | |
| C — Persistence | RDB read on boot, AOF write (`always`/`everysec`/`no`), RDB snapshot | |
| D — Data types | Lists, Sorted Sets (hand-written skip list), Hashes, Sets, `BLPOP` | |
| E — Streams | `XADD`/`XRANGE`/`XREAD` (incl. blocking), Pub/Sub | |
| F — Depth pass | NIO event loop refactor, `maxmemory` + eviction, active expiry | |
| G — Benchmarks | `redis-benchmark` results, throughput/latency numbers | |

## Architecture & Design

- **Single-threaded execution** — no lock contention; CPU is rarely the bottleneck; then the Redis 6 I/O-threads refinement.
- **Reactor pattern** — readiness selection (`epoll` / Java `Selector`) vs thread-per-connection.
- **Incremental RESP parser** — TCP fragmentation, pipelining, and the replication byte stream all use the same resumable state machine.
- **Replication** — `PSYNC` handshake, `FULLRESYNC` + RDB transfer, write propagation, `WAIT`/`ACK` offset semantics.
- **Approximate LRU** — why sampling beats an exact list.
- **Lazy + active expiration** — the two-mechanism design.
- **AOF vs RDB** — durability/performance trade-off, fsync knob, `fork()`-in-Java constraint.
- **Skip list vs balanced tree** — why Redis chose skip lists and what it means to implement one.

## Tech

- Java 25, Gradle
- JUnit (unit tests), JMH (micro-benchmarks)