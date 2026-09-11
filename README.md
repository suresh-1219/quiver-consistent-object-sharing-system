# Quiver: Consistent Object Sharing System

A distributed object synchronization system in Java, built from scratch using CRDTs (Conflict-free Replicated Data Types), vector clocks, and a gossip-style sync protocol over raw sockets — no external database, message broker, or framework.

## 📌 Project Overview

Quiver lets multiple independent nodes each hold their own copy of a set of named objects, update them locally and offline, and converge to the same final state once they sync with each other — without any central coordinator. It demonstrates core distributed-systems building blocks: conflict-free replicated data types, causal ordering with vector clocks, peer-to-peer gossip sync, and a simple ownership/permission model.

## 🚀 Features

- **CRDT-based conflict resolution** (`LWWRegister`) — concurrent updates from different nodes are detected and deterministically resolved, so all replicas converge to the same value
- **Vector clocks** for causal ordering — every update carries a vector clock so nodes can tell whether one update happened BEFORE, AFTER, or CONCURRENT with another
- **Gossip-style sync protocol** over raw TCP sockets — nodes broadcast updates to peers and can request a full sync to catch up on missed changes
- **Ownership and permission model** — each object has an owner, and the owner can grant write access to other nodes, with grants propagated to peers
- **Config-driven peer discovery** via `config.json`, with a hardcoded fallback
- **Interactive CLI** — `create`, `update`, `set-permission`, `status`, `list`, `exit`
- **Thread-safe object store** using `ConcurrentHashMap`, since the store is accessed concurrently by the CLI thread and per-connection network threads

## ✅ Testing

- 16 JUnit 5 tests covering:
  - Vector clock comparison (BEFORE / AFTER / CONCURRENT / EQUAL)
  - LWWRegister convergence — two replicas reach the same value after merging concurrent updates
  - Deterministic tie-breaking on concurrent conflicting updates
  - Stale update rejection
  - Object ownership, write permission checks, and permission granting

## 🛠️ Tech Stack

| Technology | Usage |
|---|---|
| Java 17 | Programming Language |
| Raw TCP Sockets | Node-to-node networking |
| Gson | JSON message (de)serialization |
| Custom CRDTs | Conflict-free state convergence |
| JUnit 5 | Testing |
| Maven | Build Tool |

## 🏗️ Architecture

```text
        ┌─────────────┐        gossip sync         ┌─────────────┐
        │   Node A    │ ◄─────────────────────────► │   Node B    │
        │ ObjectStore │                              │ ObjectStore │
        │ (CRDT state)│ ◄───────────┐  ┌───────────► │ (CRDT state)│
        └─────────────┘             │  │             └─────────────┘
               ▲                    ▼  ▼                    ▲
               │              ┌─────────────┐                │
               └─────────────►│   Node C    │◄───────────────┘
                              │ ObjectStore │
                              └─────────────┘
```

Each node runs a `NodeServer` (listens for peer connections + gossip messages) alongside a CLI loop for local commands. Local updates are applied to the node's `ObjectStore` and broadcast to all known peers; incoming remote updates are merged via the CRDT's conflict-resolution rules.

## ⚙️ Getting Started

### Prerequisites

- Java 17+
- Maven

### Build

```bash
mvn clean package
```

### Run a 3-node cluster

Open three terminals and start one node in each:

```bash
java -jar target/quiver-0.0.1-SNAPSHOT.jar --node-id=A --port=9001
java -jar target/quiver-0.0.1-SNAPSHOT.jar --node-id=B --port=9002
java -jar target/quiver-0.0.1-SNAPSHOT.jar --node-id=C --port=9003
```

Peers are loaded from `src/main/resources/config.json`:

```json
{
  "nodes": [
    { "nodeId": "A", "host": "localhost", "port": 9001 },
    { "nodeId": "B", "host": "localhost", "port": 9002 },
    { "nodeId": "C", "host": "localhost", "port": 9003 }
  ]
}
```

### CLI commands

| Command | Example | Description |
|---|---|---|
| `create <name>` | `create doc1` | Create a new object, owned by this node |
| `update <name> <data>` | `update doc1 hello` | Update an object's value (owner or granted writers only) |
| `set-permission <name> <read/write> <user_id>` | `set-permission doc1 write B` | Grant another node write access (owner only) |
| `status <name>` | `status doc1` | Show an object's current value |
| `list` | `list` | List all known objects |
| `exit` | `exit` | Shut down the node |

### Try it out

1. On Node A: `create doc1`
2. On Node B: `status doc1` — should show up after the gossip sync
3. On Node A: `update doc1 hello-from-A`
4. On Node C: `status doc1` — should converge to the same value

### Run tests

```bash
mvn test
```

## 📄 License

This project was built as a personal portfolio project to explore distributed systems concepts.
