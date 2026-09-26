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
- **Authenticated sync protocol** over raw TCP — every message carries an Ed25519
  signature from its sender's own private key, and writes are checked against the
  object's ACL before they are applied
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
an **Ed25519 signature** over all of them, made with the sender's own private key.
Receivers verify against the sender's *public* key — listed in `config.json`, the way an
SSH `known_hosts` entry lists a host's public key — and reject anything that fails
verification, comes from an unrecognised node, falls outside a 60-second freshness
window, or replays a nonce already seen. Writes are then authorised against the object's
ACL, so an authenticated-but-unauthorised peer still cannot change an object it has no
write permission for.

This project's authentication scheme went through one real revision, and the reason is
worth stating plainly: an earlier version used a single HMAC secret shared by every node.
That proved cluster *membership* — "someone holding a valid key sent this" — but not
sender *identity*, because whatever let a node verify a peer's message would have equally
let it forge one. Ed25519 is asymmetric: verifying only needs the public key, so a peer
that can confirm "this came from A" gains no ability to produce something that looks like
it came from A. That is the property "authentication" is supposed to mean, and closing
this gap was this project's first real post-launch security fix — see git history for the
before/after.

**Key distribution**, since asymmetric crypto raises a question HMAC's single shared
secret didn't: `NodeKeyStore.resolve()` looks for a node's private key in this order —
an explicit `--key-file`, then `<data-dir>/<node-id>.key`, then a bundled demo identity
(see below), then generates a fresh one and prints the public key to paste into
`config.json`. Public keys are safe to commit; a node's own private key never should be
— `quiver-data/` (the default location one would land in) is gitignored for exactly this
reason.

**The bundled demo identities** (`src/main/resources/demo-keys/`) are what let `mvn
clean verify && java -jar quiver.jar --node-id=A` work with zero setup, matching
`config.json`'s bundled public keys out of the box. Using one prints a loud runtime
warning, because these private keys ship inside the jar and are public knowledge — fine
for trying the project out, never acceptable for anything real. Delete
`quiver-data/<node-id>.key` if one exists and start fresh (or pass `--key-file`) to get a
real, private identity instead.

What this still does **not** give you:

- **Confidentiality.** Traffic is signed, not encrypted — anyone watching the network can
  read message contents, just not forge or silently tamper with them.
- **Key revocation or rotation.** If a private key is compromised, the fix today is
  generating a new one and manually updating `config.json` on every peer. There is no
  revocation list or expiry.
- **A trust bootstrap mechanism.** `config.json` is how a node learns which public keys
  to trust in the first place, and nothing here verifies that file's contents came from
  a legitimate source — the same way a freshly-created SSH `known_hosts` file has to be
  trusted somehow the first time.

## Tech stack

| Technology | Usage |
|---|---|
| Java 17 | Language |
| Raw TCP sockets | Node-to-node networking |
| Gson | JSON (de)serialisation |
| java.security Ed25519 (JEP 339) | Message authentication, no external crypto library |
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
points at a different cluster definition, `--data-dir=path` controls where each node's
journal *and* identity file live (defaults to `./quiver-data`; each node writes its own
`<node-id>.jsonl` and reads/generates its own `<node-id>.key` inside it), and
`--key-file=path` overrides just the identity file's location:

```json
{
  "nodes": [
    { "nodeId": "A", "host": "127.0.0.1", "port": 9001, "publicKey": "MCowBQYDK2VwAyEAOh+RtiIhe+ieGEaMWa1kGHOIxCyrIhWINpGt+Iky5l0=" },
    { "nodeId": "B", "host": "127.0.0.1", "port": 9002, "publicKey": "MCowBQYDK2VwAyEAY6phr2VMX1fW5SIAWoNBfMTRSXvPi/t7CDwGwUXvwJg=" },
    { "nodeId": "C", "host": "127.0.0.1", "port": 9003, "publicKey": "MCowBQYDK2VwAyEAmGdX5z9US+sIO73hFbGzHa7KmH/9396R9GVxUojYjpA=" }
  ]
}
```

Every node needs the same file: these public keys are how a node verifies who a message
actually came from. The three keys above match the bundled demo identities described in
"Security model" — real, working Ed25519 keys, just not private ones worth trusting for
anything beyond trying the project out.

### Using a real identity instead of the demo keys

The commands above use the bundled demo identities (see "Security model") — fine for
trying things out, not for anything you'd trust. To use a real one:

```bash
java -jar target/quiver-0.0.1-SNAPSHOT.jar --node-id=A --data-dir=my-data
```

With no `my-data/A.key` yet, this generates a fresh Ed25519 identity, saves it there, and
prints something like:

```
Generated a new identity for node 'A' at /path/to/my-data/A.key. Add this to
config.json so peers trust it:
  "publicKey": "<a real base64 public key>"
```

Paste that `publicKey` value into every peer's `config.json` for node A, and `my-data/`
(gitignored) now holds A's real private key instead of the demo one.

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
- **No key revocation, rotation, or trust bootstrap.** See "Security model" — closing the
  non-repudiation gap didn't remove every open question asymmetric crypto raises, just
  the one this project set out to fix.

## License

MIT — see [LICENSE](LICENSE).
