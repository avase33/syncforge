# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/); versioning: [SemVer](https://semver.org/).

## [0.1.0] - 2026-07-22

Initial release — a multiplayer state-sync server whose board converges by
construction, not by coordination.

### Added
- **From-scratch CRDT**: a last-write-wins register keyed by a Lamport
  `(counter, replica)` stamp — a total order, so "last write" is unambiguous
  between replicas that never communicate. Registers compose into per-field
  shapes and a shape-id map, each merge a semilattice join, so the whole board
  is commutative, associative and idempotent.
- **Ops = state**: an operation is a one-shape fragment applied with the same
  join as full-state merge, making syncforge simultaneously op-based and
  state-based; live deltas and catch-up snapshots interleave freely.
- **Tombstone deletes** and **per-field registers**, so a delete racing an edit,
  or two people editing one shape, both converge without data loss.
- **Virtual-thread WebSocket transport** (`/rooms/{roomId}`): one Loom thread per
  connection, snapshot on join, op relay, ephemeral cursor presence, leave
  notices.
- **Pluggable fan-out**: in-memory single-node broadcaster by default, or Redis
  Pub/Sub across instances under the `redis` profile — correct without ordering
  or exactly-once guarantees because ops are idempotent.
- **gRPC control-plane contract** (`proto/control.proto`) with an in-process
  default implementation for room ownership and auth.
- **Read API** (`/api/rooms`, `/api/rooms/{id}/snapshot`) and three **demo bots**
  that draw in a `demo` room on boot, so the canvas is live with real merges
  before any human connects.
- **TypeScript canvas client** running the same CRDT locally for instant,
  optimistic edits and live remote cursors.
- 16 JUnit tests including a randomized convergence property test (1,500 ops ×
  400 shuffled, duplicated replays) and an 8-thread room concurrency test;
  Dockerfiles, docker-compose with a Redis multi-instance profile, GitHub
  Actions CI, Makefile, MIT license.
