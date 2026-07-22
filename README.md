# syncforge ⚡

**A multiplayer state-sync server.** Many people edit one board at once; edits
arrive out of order, duplicated, or after a reconnect — and everyone still ends
up looking at exactly the same thing. The guarantee is not "usually consistent."
It is **convergence by construction**, proven by a property test that replays
thousands of randomized, duplicate-laden schedules and asserts they all land on
one board.

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
| **Control plane** | gRPC contract *(in-process default)* | Room ownership, auth, cross-instance op stream |
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
  connection (and per demo bot), so tens of thousands of idle collaborators cost
  almost nothing. `spring.threads.virtual.enabled=true`.

## Testing

```bash
make test        # or: cd server-java && mvn test
```

16 tests. The headline is `BoardCrdtConvergenceTest`: it generates one random
history of 1,500 ops across five replicas, then replays it in 400 different
shuffled orders — applying every op twice — and asserts every replay equals the
reference board. It also checks that state-merge reconciliation matches op
replay, that a delete racing a move resolves identically on both sides, and
`RoomConcurrencyTest` pounds a single room with 8 threads × 500 ops and asserts
the result matches a serial replay.

## A note on the stack

The blueprint named Redis, gRPC and raw Netty. syncforge implements the ideas
those pieces stand for, and is explicit about which are load-bearing here:

- **The CRDT is the real work** and is written from scratch — no Yjs, no
  Automerge — so the convergence argument is inspectable rather than trusted.
- **Redis Pub/Sub** is a genuine, working adapter (`bus/RedisBroadcaster.java`,
  Lettuce) behind the `redis` profile; the default swaps in an in-process
  broadcaster so the server runs with nothing installed.
- **gRPC** is the documented internal control-plane contract
  (`proto/control.proto`, `control/ControlPlane.java`) with an in-process
  implementation. A single node needs no gRPC runtime; sharding rooms across a
  fleet is where you bind it to grpc-java.

## Layout

```
proto/protocol.md      WebSocket op + snapshot + presence contract
proto/control.proto    internal gRPC control-plane contract
server-java/           CRDT, rooms, WebSocket, Redis fan-out, REST read API
client-ts/             Canvas whiteboard with a client-side CRDT mirror
docs/ARCHITECTURE.md
```

## License

MIT © 2026 Akhil Vase
