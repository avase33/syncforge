# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Described the control plane accurately. `proto/control.proto` is a design
  document that no part of the build compiles; `InProcessControlPlane` is the
  only implementation. Removed references to a `GrpcControlPlane` class and a
  `grpc` Maven profile, neither of which exists, from the control-plane javadoc,
  `control.proto` and `protocol.md`.
- Replaced the unbenchmarked "tens of thousands of idle collaborators cost
  almost nothing" claim in the README and `application.yml` with the design
  rationale behind it — why a parked virtual thread is cheap — since this
  repository ships no benchmark harness.
- Documented in the README how to run the CRDT convergence test and which
  invariant it establishes.

### Removed

- `docs/architecture.md`, a generated template that collided on
  case-insensitive filesystems with the real `docs/ARCHITECTURE.md` and
  described a design this project does not have (no repository layer, no
  database, no caching tier).
- `docs/dev-log-2026-07-22.md`, generated session boilerplate with nothing in it
  specific to this project.

## [0.1.0] - 2026-07-22

Initial version. Never tagged or published as a release.

### Added

- **CRDT core** (`server-java/src/main/java/com/syncforge/crdt/`), written from
  scratch — no Yjs, no Automerge. `LwwRegister` is a last-write-wins register
  over `Stamp`, a Lamport counter paired with a replica id that together form a
  total order, so "last write" is unambiguous between replicas that never
  communicate. `Shape` is a map of independent per-field registers, so a
  concurrent drag and recolour of the same shape both survive. `BoardCrdt`
  composes a map union over those per-field joins. Deletion is a tombstone
  (`deleted = true` written through the same LWW path), never an erase.
- **Op-based and state-based in one algebra.** `Operation` is a one-shape
  fragment, and applying it uses the same join as full-state merge, so a live op
  stream and a catch-up snapshot can interleave in any order without disagreeing.
- **16 tests.** `BoardCrdtConvergenceTest` generates one random history of 1,500
  ops across 5 replicas, then replays it in 400 shuffled orders with every op
  applied twice, asserting each replay equals the reference board. It also
  checks that state-merge reconciliation matches op replay, and that a delete
  racing a move resolves identically on both replicas. `RoomConcurrencyTest`
  drives a single room with 8 threads x 500 ops and asserts the result equals a
  serial replay.
- **WebSocket transport** on Spring WebSocket with
  `spring.threads.virtual.enabled=true`: one virtual thread per connection, op
  and presence frames, and a snapshot on join. Contract in `proto/protocol.md`.
- **Presence** (`room/Presence.java`), deliberately outside the CRDT — cursors
  are ephemeral, last-write-per-site, never merged into the board or included in
  a snapshot, and dropped the instant a session disconnects.
- **Rooms** (`room/RoomManager.java`, `room/Room.java`): each room owns one
  board and its own sessions, and shares no state with any other room.
- **Fan-out seam** (`bus/Broadcaster.java`) with two adapters —
  `InMemoryBroadcaster` by default, so the server runs with no external
  services, and `RedisBroadcaster` (Lettuce) under the `redis` profile for
  multi-instance deployments. Because ops are idempotent deltas, the fan-out
  needs no ordering and no exactly-once delivery to stay correct.
- **REST read API** (`api/BoardController.java`): `GET /api/health`,
  `GET /api/rooms`, `GET /api/rooms/{roomId}/stats`, and
  `GET /api/rooms/{roomId}/snapshot`.
- **Demo collaborators** (`demo/DemoBots.java`): three synthetic bots drawing in
  the `demo` room on boot, each its own replica on its own virtual thread, so
  the canvas shows real CRDT merges before any human connects.
- **TypeScript canvas client** (`client-ts/`) running a mirror of the same CRDT
  in the browser, so local edits apply optimistically and match what the server
  converges to.
- **Docker Compose** setup, including a `redis` profile that starts Redis plus
  two server instances on `:8080` and `:8081`.
- **CI** (`.github/workflows/ci.yml`): Java 21 test and package, TypeScript
  type-check and build.

[Unreleased]: https://github.com/avase33/syncforge/compare/main...HEAD
