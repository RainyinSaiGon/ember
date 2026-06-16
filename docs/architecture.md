# Architecture

## Overview

Ember is a single-threaded, NIO-event-loop TCP server that speaks the Redis wire
protocol (RESP). Many clients connect; one thread handles all of them.

```
Many TCP clients ──────────────────────────────────────────────────┐
                                                                    ▼
                                           ┌──────────────────────────────────┐
                                           │  NIO Selector  (1 thread)         │
                                           │  com.authlyn.server               │
                                           └──────────────┬───────────────────┘
                                                          │ raw bytes
                                                          ▼
                                           ┌──────────────────────────────────┐
                                           │  RESP Parser  (incremental)       │
                                           │  com.authlyn.protocol             │
                                           └──────────────┬───────────────────┘
                                                          │ Command objects
                                                          ▼
                                           ┌──────────────────────────────────┐
                                           │  Command Dispatcher               │
                                           │  com.authlyn.command              │
                                           └──────────────┬───────────────────┘
                                                          │ mutate / read
                                                          ▼
                         ┌────────────────────────────────────────────────────┐
                         │  Keyspace  HashMap<String, RedisObject>             │
                         │  + expires map  (key → expiry timestamp ms)         │
                         │  com.authlyn.store                                  │
                         └───────────┬────────────────────────┬───────────────┘
                                     │                         │
                           active expiry                  persistence
                         (sampled sweep)            com.authlyn.persistence
                                                    RDB read  +  AOF write
```

Replication runs as a special long-lived connection: the replica connects to the
master, completes the `PSYNC` handshake, receives an RDB snapshot, then receives
every subsequent write command on the same link.

```
Master  ──── propagated writes (RESP) ────▶  Replica
        ◀─── REPLCONF ACK <offset>  ────────
```

Both master and replica use the same engine; `com.authlyn.replication` handles
the handshake and offset accounting.

## Package map

| Package | Responsibility |
|---|---|
| `com.authlyn` | Entry point (`Main.java`) |
| `com.authlyn.server` | `ServerSocket` / NIO `Selector`, connection accept, read/write readiness, per-connection buffers |
| `com.authlyn.protocol` | Incremental RESP parser (state machine over a `ByteBuffer`), RESP encoder |
| `com.authlyn.command` | Command dispatcher (pattern-match on command name), one handler class per command group |
| `com.authlyn.store` | `Keyspace` (`HashMap<String, RedisObject>`), `RedisObject` sealed type (String, List, Hash, Set, SortedSet), `expires` map |
| `com.authlyn.persistence` | RDB binary format reader (load on boot), AOF command-log writer (fsync policy), RDB snapshot writer |
| `com.authlyn.replication` | Master/replica handshake (`PING` → `REPLCONF` → `PSYNC`), write propagation, `REPLCONF ACK`/`WAIT` offset tracking |

## Key invariant

Command execution is **single-threaded**. The `Keyspace` is touched by exactly
one thread — the event loop — so there are no locks and no races. Concurrency
lives only at the I/O edges (socket reads/writes). This is the same design
Redis uses; the bottleneck is network and memory bandwidth, not CPU contention.
