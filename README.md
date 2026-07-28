# syncforge ⚡

**A multiplayer state-sync server.** Many people edit one board at once; edits
arrive out of order, duplicated, or after a reconnect — and everyone still ends
up looking at exactly the same thing. The guarantee is not "usually consistent."
It is **convergence by construction**, backed by a property test that replays a
1,500-op history in 400 randomized, duplicate-laden orders and asserts every one
of them lands on the same board.

```
   browser (canvas + client CRDT)
        │  op / cursor  (WebSocket JSON)
        ▼
┌───────────────────────────┐        ┌───────────────────────────┐
│ Server A · Java 21        │◀──────▶│ Server B · Java 21        │
│  virtual-thread WS        │ Redis  │  (same room, other users) │
│  BoardCRDT (LWW map)      │ Pub/Sub│                           │
└──────────┬────────────────┘        └───────────────────────────┘
           │ merge (order-independent)
           ▼
     authoritative board  ──snapshot──▶ late joiners catch up in one frame
```

| Layer | Technology | Owns |
| --- | --- | --- |
| **Transport** | Java 21 · Spring WebSocket | One virtual thread per connection; op + presence frames |
| **State** | Pure Java CRDT | LWW-map of shapes, each field an LWW register; converges under any order |
| **Fan-out** | In-memory *(default)* · Redis Pub/Sub | Replicating ops to other server instances |
| **Control plane** | Plain Java interface, in-process impl | Room ownership, auth (gRPC version is designed, not built — see below) |
| **Client** | TypeScript · Canvas | A collaborative whiteboard running the *same* CRDT locally |

**The default profile runs the whole thing with no external services** — no
Redis, no database. It even boots three synthetic collaborators that draw in a
`demo` room, so the canvas is alive with real CRDT merges the moment you open it.

## Quickstart

```bash
cd server-java && mvn spring-boot:run     # :8080, demo room already moving
cd client-ts   && npm install && npm run dev   # :3000
```

Open **http://localhost:3000** twice (or in two browsers). Double-click to drop
a shape, drag to move it, and watch the other tab follow. The little arrows are
other people's live cursors.

`http://localhost:8080/api/rooms` shows live room stats;
`http://localhost:8080/api/rooms/demo/snapshot` returns the board as JSON.

## Running more than one instance

```bash
SYNCFORGE_PROFILE=redis docker compose --profile redis up --build
```

That starts Redis and two servers (`:8080` and `:8081`). Connect one browser to
each — they are different processes with different in-memory boards — and they
still converge, because every op is republished over Redis Pub/Sub and merged
independently on both sides. Since ops are idempotent CRDT deltas, the fan-out
needs no ordering or exactly-once delivery to be correct.

## The interesting engineering

- **A board that cannot diverge.** Every mutable property of every shape is a
  last-write-wins register stamped with a Lamport clock plus a replica id. That
  `(counter, replica)` pair is a *total* order, so "last write" is unambiguous
  even between replicas that never talk directly. Register merge is a semilattice
  join — commutative, associative, idempotent — and the board composes a map
  union over per-field joins, so it inherits all three properties. That is the
  entire reason order, duplication, and reconnects are harmless.
  `crdt/LwwRegister.java`, `crdt/BoardCrdt.java`

- **Per-field registers, not per-shape.** If Alice drags a shape while Bob
  recolours it, both edits survive, because `x`/`y` and `color` are independent
  registers. A coarser "one register per shape" model would make the later edit
  silently erase the earlier one. `crdt/Shape.java`

- **Delete is a tombstone, not an erase.** Deletion writes `deleted = true`
  through the same LWW path, so a delete racing a concurrent edit converges like
  any other field, and an undo is just a later `deleted = false`.

- **Ops and state are the same join.** An operation is a one-shape fragment, and
  applying it uses the identical merge as full-state reconciliation. So syncforge
  is simultaneously an op-based and a state-based CRDT: a live op stream and a
  catch-up snapshot can be mixed freely and never disagree. `crdt/Operation.java`

- **The client runs the CRDT too.** The browser applies your own edits
  optimistically and merges everyone else's with the same rules, so dragging is
  instant and what you see locally is what the server converges to.
  `client-ts/src/crdt.ts`

- **Virtual threads for the socket layer.** One Loom virtual thread per
  connection (and per demo bot), via `spring.threads.virtual.enabled=true`. The
  reason that fits this workload: a collaborative session is idle almost all the
  time — blocked on a socket read, waiting for a human to move a mouse. A parked
  virtual thread is a heap object holding its continuation, not an OS thread
  holding a stack reservation, so idle connections consume memory rather than
  scheduler resources and stop being the axis you scale on. It buys the clarity
  of blocking, one-thread-per-connection code without paying per-connection
  platform-thread costs. *(This is the design rationale, not a measurement —
  there is no benchmark harness in this repository, so take no throughput or
  connection-count number from it.)*

## Testing

```bash
make test                       # or: cd server-java && mvn test
```

16 tests across six classes. To run only the headline one:

```bash
cd server-java && mvn -B test -Dtest=BoardCrdtConvergenceTest
```

### The convergence property

`server-java/src/test/java/com/syncforge/crdt/BoardCrdtConvergenceTest.java` is
the test the rest of the project exists to earn. The invariant it establishes:

> Given the same **set** of operations, every replica converges to the same
> board — regardless of the **order** it applies them in, and regardless of **how
> many times** it sees each one.

That is the whole correctness argument for the system. If it holds, then network
reordering, duplicate delivery, and a client reconnecting with a stale backlog
are all non-events; if it fails, no amount of transport-layer care can save the
board. So the test attacks it directly rather than checking a few hand-picked
interleavings:

| What it does | Numbers |
| --- | --- |
| Generate one random op history across independent replicas | 1,500 ops, 5 replicas, 12 shapes, 8 fields, fixed seed |
| Build a reference board by applying it in generation order | 1 board |
| Replay that same history into a fresh board, shuffled | 400 independent shuffles |
| Apply every op **twice** on each replay (idempotency under redelivery) | 3,000 applications per shuffle |
| Assert each replay equals the reference | 400 assertions, all must pass |

The seed is fixed, so a failure is reproducible rather than a flake you can
shrug off. Three companion tests close the remaining gaps:

- **`stateMergeConvergesJustLikeOpReplay`** — partitions a history across three
  boards, reconciles them by whole-state `merge` in an arbitrary order (merging
  one board in twice), and asserts the result equals the op-replay reference.
  This is what proves op-apply and state-merge are the *same join*, which is why
  a snapshot and a live op stream can interleave freely.
- **`concurrentDeleteAndEditResolveByStampEverywhere`** — Alice deletes a shape
  while Bob, having seen the create, moves it. Both edits must survive (they
  touch independent registers) and both replicas must agree whichever order the
  three ops arrive in.
- **`anEmptyOpHistoryYieldsAnEmptyBoard`** — the base case.

Beyond the CRDT itself, `RoomConcurrencyTest` drives a single room with 8 threads
× 500 ops and asserts the outcome equals a serial replay, and `WireCodecTest`
checks that ops and snapshots survive a JSON round-trip with their stamps intact.

## A note on the stack

The blueprint named Redis, gRPC and raw Netty. syncforge implements the ideas
those pieces stand for, and is explicit about which are load-bearing here:

- **The CRDT is the real work** and is written from scratch — no Yjs, no
  Automerge — so the convergence argument is inspectable rather than trusted.
- **Redis Pub/Sub** is a genuine, working adapter (`bus/RedisBroadcaster.java`,
  Lettuce) behind the `redis` profile; the default swaps in an in-process
  broadcaster so the server runs with nothing installed.
- **gRPC is designed but not built.** Room ownership and auth are factored out
  behind `control/ControlPlane.java`, a plain Java interface whose one
  implementation is in-process. `proto/control.proto` writes down what that seam
  would look like as a gRPC service — and nothing compiles it: there is no
  protobuf plugin and no grpc-java dependency in `pom.xml`, so no stubs are
  generated and no code here speaks gRPC. Read the `.proto` as a specification.
  Wiring it up is the work a multi-node deployment would take on; the
  cross-instance op stream it describes is served today by Redis Pub/Sub instead.

## Layout

```
proto/protocol.md      WebSocket op + snapshot + presence contract (implemented)
proto/control.proto    control-plane contract — design document, not compiled
server-java/           CRDT, rooms, WebSocket, Redis fan-out, REST read API
client-ts/             Canvas whiteboard with a client-side CRDT mirror
docs/ARCHITECTURE.md   Why a CRDT, how register → shape → board composes
```

## License

MIT © 2026 Akhil Vase
