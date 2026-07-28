# syncforge architecture

The bottleneck this attacks: **concurrent edits to shared state corrupt each
other.** Two users drag the same shape, two servers receive edits in different
orders, a client reconnects and replays a queue — and a naive "last message
wins over the whole object" server loses data every time. syncforge makes the
loss impossible by construction rather than patching it with locks.

```
        browser tab                         browser tab
        canvas + client CRDT                canvas + client CRDT
             │  op (WebSocket JSON)               │
             ▼                                     ▼
   ┌───────────────────────┐   Redis Pub/Sub ┌───────────────────────┐
   │ Server A              │◀───────────────▶│ Server B              │
   │  WsHandler (vthreads) │   (redis prof.)  │                       │
   │  RoomManager          │                  │                       │
   │  Room → BoardCRDT     │                  │  Room → BoardCRDT     │
   └───────────────────────┘                  └───────────────────────┘
```

## Why a CRDT and not a lock

The obvious design is a lock or a single writer: serialize every edit through one
authority so there are never two concurrent writes. It works until it doesn't —
it adds latency to every edit, it falls apart across multiple servers, and it
cannot handle a client that was briefly offline and now has a backlog to flush.

A CRDT inverts the problem. Instead of preventing concurrency, it defines a merge
that makes concurrency *safe*: any two states have a unique least-upper-bound,
and merging is commutative, associative and idempotent. Given those properties,
the order edits arrive in cannot matter, duplicates cannot matter, and a
reconnecting client just merges — there is nothing to coordinate.

## The register is the atom

Every mutable property — `x`, `y`, `w`, `h`, `color`, `type`, `z`, `deleted` — is
a **last-write-wins register**: a value plus a `Stamp`, where a stamp is a
Lamport counter and the writing replica's id.

The stamp is a **total order**: compare counters, break ties on replica id. Two
replicas that have never exchanged a message still agree on which of two writes
is "later," because the comparison is deterministic and defined on data both
already hold. Merge keeps the value with the greater stamp. That single operation
is the whole semilattice join:

    merge(a, b) = merge(b, a)                 (commutative — max ignores order)
    merge(merge(a,b),c) = merge(a,merge(b,c)) (associative)
    merge(a, a) = a                           (idempotent — redelivery is free)

The Lamport clock's job is to make causality show up in the order: when a replica
witnesses a remote stamp it fast-forwards its counter, so any write it makes
*after* seeing something is guaranteed to dominate it. Concurrent writes (neither
saw the other) fall back to the replica-id tie-break — arbitrary, but identical
everywhere.

## From register to board

- A **shape** is a map of named registers. Making each field independent is what
  lets a drag (`x`, `y`) and a recolour (`color`) on the same shape both survive;
  they touch disjoint registers.
- The **board** is a map from shape id to shape. Its merge is a map union whose
  per-key conflicts are resolved by shape merge, which is resolved by register
  merge. A composition of joins is a join, so the board is a CRDT with no extra
  proof obligation.
- **Deletion** is an LWW write of `deleted = true` — a tombstone. A delete racing
  a concurrent edit converges like any other field, and undo is a later
  `deleted = false`. Nothing is ever physically removed, which is precisely why
  a late op referring to a "deleted" shape cannot resurrect corruption.

`BoardCrdtConvergenceTest` is the proof in code: one random history, hundreds of
shuffled and duplicated replays, all asserted equal.

## Ops and snapshots are the same algebra

An **operation** is just the changed fields of one shape, each carrying its
stamp. Applying an op is the exact same per-field join as merging whole state.
That equivalence is the point: syncforge is at once

- **operation-based** — the live path ships tiny deltas over WebSocket, and
- **state-based** — a joining client gets one snapshot frame and merges it.

Because both are the same join, the two can interleave arbitrarily. A client can
receive a snapshot, then ops that predate it, then duplicates of those ops, in
any order, and converge. No sequence numbers, no gap detection, no reordering
buffer.

## Keyed by room, sharded by nothing

Each room owns one board and the sessions viewing it. Rooms share no state, so
they are embarrassingly parallel — different rooms can live on different threads
or different servers with zero coordination. Within a room, `Room` takes one
short lock around board mutation; the CRDT itself is lock-free, and this is the
only place that serializes it. `RoomConcurrencyTest` drives one room with 8
threads × 500 ops and asserts the outcome equals a serial replay.

## Presence is deliberately not a CRDT

Cursors and selections are ephemeral. There is nothing to converge — a cursor
position from a moment ago is simply wrong — so presence is last-write-per-site,
never merged into the board, never in a snapshot, and dropped the instant a
session disconnects. Conflating it with document state is a classic mistake that
either pollutes history with cursor spam or makes cursors durable when they
should evaporate.

## Scaling out

One instance fans an op to its own sessions in-process. The `Broadcaster` seam
carries the same op to other instances:

- **Default** — `InMemoryBroadcaster`: this JVM is the whole fleet, publish is a
  no-op.
- **`redis` profile** — `RedisBroadcaster`: publish each op to a per-room Redis
  channel; every instance subscribes and applies ops that are not its own echo.

Idempotent deltas mean the fan-out needs no ordering or exactly-once guarantee —
Redis Pub/Sub's at-most-once, unordered delivery cannot desynchronise a board,
only delay one client's view by a frame.

Room ownership and auth are kept out of the transport layer behind
`control/ControlPlane.java`, whose sole implementation is in-process (one node
owns every room, permissive auth). `proto/control.proto` sketches that seam as a
gRPC service and is **not compiled by the build** — no protobuf plugin, no
grpc-java dependency, no generated stubs, nothing here speaking gRPC. It is a
written-down contract for a multi-node deployment, not shipped functionality.

## Offline-first

Default profile: virtual-thread WebSocket server, in-memory fan-out, three demo
bots drawing in the `demo` room. `mvn spring-boot:run` is a complete
collaborative server with faults-free convergence visible in under a second,
configuring nothing. `docker compose --profile redis up` swaps the fan-out for
Redis and adds a second instance; the CRDT, room and transport layers do not
change.
