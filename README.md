# Quiver: Consistent Object Sharing System

A distributed object synchronisation system in Java, built from scratch with CRDTs
(conflict-free replicated data types), vector clocks and a peer-to-peer sync protocol
over raw TCP sockets — no external database, message broker or framework.

## Project overview

Quiver lets several independent nodes each hold their own copy of a set of named
objects, update them locally and offline, and converge on the same final state once they
sync — without a central coordinator. It demonstrates the core building blocks of a
replicated system: conflict-free replicated data types, causal ordering with vector
clocks, authenticated peer-to-peer sync, and an ownership/permission model.

## Features

- **CRDT conflict resolution** (`LWWRegister`) — concurrent writes are detected and
  resolved identically on every replica, so all copies converge
- **Vector clocks** for causal ordering — every write carries a clock, so nodes can tell
  whether one write happened BEFORE, AFTER or CONCURRENTLY with another
- **Authenticated sync protocol** over raw TCP — messages are HMAC-signed, and writes are
  checked against the object's ACL before they are applied
- **Ownership and permissions** — each object has an owner who can grant write access;
  grants propagate to peers and are only honoured when they come from the owner
- **Full state transfer** — a node that joins late asks its peers for a snapshot and
  receives values, ownership and ACLs
- **Config-driven membership** via `config.json`, validated at start-up
- **Crash-durable persistence** — every successful mutation is appended to an on-disk
  journal and replayed on restart, so a node's state survives a restart even with every
  peer offline
- **Interactive CLI** — `create`, `update`, `set-permission`, `status`, `list`, `help`, `exit`

## Design notes

### Two clocks per register

`LWWRegister` keeps the clock of the value it currently holds (`valueClock`) separately
from the join of every clock it has ever seen (`seenClock`).

- `valueClock` is what conflict resolution compares against, so a losing merge never
  inflates the winner's timestamp.
- `seenClock` is what a local write is stamped from, so a replica's counter can never go
  backwards after it loses a conflict.

A single clock cannot do both jobs: if the register adopts the winner's clock wholesale,
the loser forgets its own counter and reissues a value it has already used, which breaks
the vector-clock invariant that a node's counter is monotonic.

### Why conflicts are resolved with a total order

Causal comparison is a *partial* order — two concurrent writes are simply incomparable.
Merging must nevertheless be commutative and associative, so `VectorClock.TOTAL_ORDER`
extends causality into a strict *total* order: compare the sum of the counters first
(a dominating clock always has a strictly larger sum, so this never contradicts
causality), then the canonical string form, then the writer id, then the value. Merging
is then "keep the maximum", which is trivially commutative, associative and idempotent —
so replicas converge regardless of delivery order or duplicates. This is checked by a
randomised test that replays each write set in many shuffled orders and asserts that all
replicas agree.

### Persistence: a journal of snapshots, not events

Each successful mutation is appended to `<data-dir>/<node-id>.jsonl` as a full
`ObjectSnapshot` — value, clock, owner and ACL for the one object that changed — rather
than as a command to replay. This is deliberate: a snapshot is exactly what peer state
transfer (`STATE` messages) already sends and merges, so replaying the journal on startup
reuses the same `applyOneSnapshot` path, with the same convergence guarantee, instead of
needing a second, separately-tested notion of "replay a write." A torn last line from a
crash mid-append is skipped, not fatal — everything before it still applies. The
trade-off is that the file grows by one line per mutation forever; a real deployment
would periodically compact it down to one line per object using `ObjectStore.snapshot()`.

## Security model

Every message is wrapped in an envelope carrying the sender id, a timestamp, a nonce and
an HMAC-SHA256 tag over all of them. Receivers reject messages that fail the MAC check,
come from an unknown node, fall outside a 60-second freshness window, or replay a nonce
they have already seen. Writes are then authorised against the object's ACL, so an
authenticated-but-unauthorised peer still cannot change an object it has no write
permission for.

What this does **not** give you:

- **Non-repudiation.** Keys are symmetric and shared through `config.json`, so a node
  that can verify a peer could also impersonate it. Per-node key pairs (Ed25519) or mTLS
  is the natural next step.
- **Confidentiality.** Traffic is signed, not encrypted.
- The bundled secrets in `config.json` are development placeholders. Replace them.

## Tech stack

| Technology | Usage |
|---|---|
| Java 17 | Language |
| Raw TCP sockets | Node-to-node networking |
| Gson | JSON (de)serialisation |
| javax.crypto HMAC-SHA256 | Message authentication |
| Custom CRDTs | Conflict-free convergence |
| JUnit 5 | Unit and socket-level integration tests |
| Maven | Build |
| GitHub Actions | CI |

## Architecture

```text
        ┌─────────────┐      authenticated sync      ┌─────────────┐
        │   Node A    │ ◄─────────────────────────►  │   Node B    │
        │ ObjectStore │                              │ ObjectStore │
        │ (CRDT state)│ ◄───────────┐  ┌───────────► │ (CRDT state)│
        └─────────────┘             │  │             └─────────────┘
               ▲                    ▼  ▼                    ▲
               │              ┌─────────────┐               │
               └─────────────►│   Node C    │◄──────────────┘
                              │ ObjectStore │
                              └─────────────┘
```

Each node runs a `NodeServer` (accepting peer connections on a bounded thread pool)
alongside a CLI loop. Local writes are applied to the node's `ObjectStore` and broadcast
to every configured peer; inbound writes are authenticated, authorised, then merged
through the CRDT.

Message types: `CREATE`, `UPDATE`, `GRANT_PERMISSION`, `SYNC_REQUEST`, `STATE`.

## Getting started

### Prerequisites

- Java 17+
- Maven 3.8+

### Build

```bash
mvn clean verify
```

### Run a 3-node cluster

`config.json` is bundled into the jar, so the nodes below find their peers with no extra
setup. Open three terminals:

```bash
java -jar target/quiver-0.0.1-SNAPSHOT.jar --node-id=A
java -jar target/quiver-0.0.1-SNAPSHOT.jar --node-id=B
java -jar target/quiver-0.0.1-SNAPSHOT.jar --node-id=C
```

Ports come from the config; `--port=9005` overrides one, `--config=path/to/config.json`
points at a different cluster definition, and `--data-dir=path` controls where each
node's journal file lives (defaults to `./quiver-data`; each node writes to its own
`<node-id>.jsonl` inside it, so nodes can share a `--data-dir` safely):

```json
{
  "nodes": [
    { "nodeId": "A", "host": "127.0.0.1", "port": 9001, "secret": "dev-secret-A-change-me" },
    { "nodeId": "B", "host": "127.0.0.1", "port": 9002, "secret": "dev-secret-B-change-me" },
    { "nodeId": "C", "host": "127.0.0.1", "port": 9003, "secret": "dev-secret-C-change-me" }
  ]
}
```

Every node needs the same file: the secrets are how nodes recognise each other.

### CLI commands

| Command | Example | Description |
|---|---|---|
| `create <name>` | `create doc1` | Create an object owned by this node |
| `update <name> <data>` | `update doc1 hello` | Write a value (owner or granted writers) |
| `set-permission <name> write <node-id>` | `set-permission doc1 write B` | Grant write access (owner only) |
| `status <name>` | `status doc1` | Show value, owner, writers and clock |
| `list` | `list` | List known objects |
| `help` | `help` | Show the command list |
| `exit` | `exit` | Shut the node down |

### Try it out

1. On node A: `create doc1`
2. On node B: `status doc1` — it appears once the create propagates
3. On node A: `update doc1 hello-from-A`
4. On node C: `status doc1` — converges to the same value
5. On node B: `update doc1 sneaky` — refused; B has no write permission
6. On node A: `set-permission doc1 write B`, then retry step 5 — now accepted everywhere

### Run tests

```bash
mvn test
```

The suite covers vector clock algebra, CRDT convergence under shuffled delivery orders,
ACL enforcement for local and remote writes, config validation, message authentication
(tampering, spoofing, replay), and socket-level integration tests that start real nodes.

## Known limitations

These are deliberate boundaries, not oversights:

- **No journal compaction.** The on-disk journal grows forever; see "Persistence" above.
- **No permission revocation.** Write ACLs are grow-only sets, which is what makes them
  safe to merge. Revocation needs a different CRDT (e.g. an add/remove set with tombstones).
- **Full-mesh broadcast, not epidemic gossip.** Every node talks to every configured peer
  directly, which is fine at this scale but does not fan out.
- **Static membership.** Nodes are listed in `config.json`; there is no join/leave protocol.
- **Symmetric keys.** See "Security model".

## License

MIT — see [LICENSE](LICENSE).
