# Design Decisions

A running log of key choices and the reasoning behind them.
Add a new entry whenever a non-obvious decision is made.

---

## Single-threaded command execution

**Decision:** one thread owns the keyspace and executes all commands.

The keyspace (`HashMap<String, RedisObject>`) is touched by exactly one thread,
so there is no need for locks, `ConcurrentHashMap`, or any synchronization
primitive on the data path. Races are structurally impossible. The bottleneck is
network I/O and memory bandwidth, not CPU contention — which is why Redis itself
uses this model. When CPU becomes the limit (Redis 6+), the refinement is to
add separate I/O threads for parsing and socket work while keeping *execution*
single-threaded; that is a later depth item (Track F).

---

## Incremental RESP parser

**Decision:** the RESP parser is a resumable state machine over a growing
`ByteBuffer`, not a line-at-a-time reader.

TCP is a byte stream. One `read()` call may deliver half a command, one and a
half commands, or fifty pipelined commands — any split is possible. A
line-at-a-time reader would block or discard partial data. The state machine
marks its position, attempts to parse a complete RESP value, and resets to the
mark if bytes run out mid-element. The same parser is reused for client
connections and the replication link — the replication stream is a
never-ending command flow where byte-exact counting is required for
`REPLCONF ACK`/`WAIT` to work.

---

## Approximate LRU/LFU eviction (not exact)

**Decision:** when `maxmemory` is exceeded, sample K random keys and evict the
best candidate according to the policy (LRU clock, LFU counter, random,
volatile-TTL) rather than maintaining a global LRU list.

An exact LRU doubly-linked list costs one pointer update per key access — at
high throughput this is measurable cache pressure and pointer churn. Redis's
research showed that sampling ~5–10 keys gets within a few percent of true LRU
hit rate at a fraction of the cost. The "approximate" qualifier is worth
stating proactively in interviews: it is a deliberate trade-off, not a
shortcut. (Track F depth item.)

---

## Lazy + active expiration (two mechanisms)

**Decision:** expired keys are removed both on access (lazy) and by a periodic
background sweep (active).

Lazy expiry alone is cheap but leaves untouched dead keys consuming memory
indefinitely. Active expiry alone requires scanning the full keyspace. Redis
combines them: on access, check and delete if expired (lazy); periodically,
sample N keys from the `expires` map, delete expired ones, and repeat if the
expired fraction is high (active probabilistic sweep). CodeCrafters covers
lazy; the active sweep is a Track F depth item.

---

## Skip list for sorted sets (not `TreeMap`)

**Decision:** the sorted set (`ZSET`) is backed by a hand-written skip list
plus a member→score `HashMap`, mirroring real Redis's implementation.

A Java `TreeMap` would pass the CodeCrafters tests, but a skip list is more
instructive and matches what Redis actually uses. Skip lists support O(log n)
rank/range queries the same way a balanced BST does, but are simpler to
implement correctly, friendlier to range iteration (no in-order traversal
bookkeeping), and historically easier to make concurrent (not relevant here,
but a good interview point). The member→score hash gives O(1) `ZSCORE` lookup
alongside the ordered structure. (Track D depth item.)

---

## No `fork()` for RDB snapshots

**Decision:** RDB snapshots use pause-and-dump rather than copy-on-write fork.

C Redis forks a child process; the child writes the snapshot while the parent
continues serving requests using the OS's copy-on-write semantics. Java has no
`fork()`. Options are: (a) pause the event loop briefly and dump synchronously
("pause-and-dump"), or (b) take a copy-on-write view of the keyspace
(a snapshot of the `HashMap` at a point in time) and serialize it on a
background thread. Ember uses pause-and-dump for simplicity; the trade-off
is a brief latency spike proportional to keyspace size. The copy-on-write
background approach would remove the pause but requires careful coordination.
State this trade-off proactively — "we chose simplicity; here is the cost."
(Track C depth item.)
