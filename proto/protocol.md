# syncforge wire protocol

Two transports carry two very different kinds of data, and keeping them separate
is the whole design.

1. **Persistent board state** travels as CRDT operations over WebSocket. Every
   op is commutative, idempotent and associative, so a client can apply them in
   any order and still converge to the same board.
2. **Ephemeral presence** (live cursors, selection) travels as fire-and-forget
   relays. It is never merged, never stored, and expires when the session drops.

## WebSocket endpoint

```
ws://<host>:8080/rooms/{roomId}
```

All frames are JSON text frames of the shape `{ "t": <type>, ... }`. `t` is the
discriminator.

### Server → client, on connect

A snapshot of the whole board, so a late joiner catches up in one frame before
live ops start flowing.

```json
{
  "t": "snapshot",
  "site": "srv-7f3a",
  "board": {
    "shapes": {
      "b1e2...": {
        "fields": {
          "type":    { "v": "rect",    "c": 4, "r": "alice" },
          "x":       { "v": 120,       "c": 9, "r": "bob"   },
          "y":       { "v": 64,        "c": 9, "r": "bob"   },
          "w":       { "v": 200,       "c": 4, "r": "alice" },
          "h":       { "v": 90,        "c": 4, "r": "alice" },
          "color":   { "v": "#3b82f6", "c": 7, "r": "alice" },
          "z":       { "v": 1,         "c": 4, "r": "alice" },
          "deleted": { "v": false,     "c": 4, "r": "alice" }
        }
      }
    }
  }
}
```

Each field is a **LWW register**: `v` is the value, and `(c, r)` is its Lamport
stamp — `c` a monotonically increasing counter, `r` the id of the replica that
wrote it. `(c, r)` is a total order (compare `c`, break ties on `r`), which is
what makes "last write" unambiguous across replicas that never talk directly.

### Client → server / server → client, live edits

```json
{
  "t": "op",
  "id": "b1e2...",
  "fields": {
    "x": { "v": 140, "c": 12, "r": "alice" },
    "y": { "v": 70,  "c": 12, "r": "alice" }
  }
}
```

An `op` is a partial shape carrying only the fields that changed, each with its
new stamp. Applying it is a per-field LWW merge: a field is overwritten **iff**
the incoming stamp is greater than the stored one. A delete is just an op that
sets `deleted` to `true`; an undelete is a later op that sets it back to `false`.
Because merge is a pure join, replaying an op is a no-op and order never matters.

The server relays every accepted op to all **other** sessions in the room (and,
under the `redis` profile, to other server instances over Pub/Sub) after merging
it into the authoritative board.

### Presence (ephemeral)

```json
{ "t": "cursor", "site": "alice", "x": 340, "y": 210, "color": "#ef4444" }
```

Relayed verbatim to the rest of the room and dropped. Never merged into the
board, never included in a snapshot. If a session disconnects the server emits
`{ "t": "leave", "site": "alice" }`.

## Internal control plane (gRPC)

Server instances and internal services (auth, room directory) speak gRPC rather
than WebSocket — see `control.proto`. The default single-node build wires an
in-process implementation, so no gRPC runtime is required to run locally; the
contract is defined here because it is the seam a multi-node deployment grows
into.
