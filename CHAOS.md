# Chaos testing against real containers

`ChaosIntegrationTest` and `ChaosClusterTest` (in `src/test/java/com/quiver/network/`)
automate everything a single JVM honestly can: killing a node without letting it run a
shutdown hook, flooding a socket with garbage, and cutting individual directed edges of
a mesh via a proxy sitting on the wire. Two things they *can't* faithfully fake no matter
how carefully they're written: a real `SIGKILL` delivered by the OS to a real separate
process, and a real network partition enforced below the application entirely. This
runbook is for those two, against the actual containers from `docker-compose.yml`.

Nothing here is automated or asserted — it's a sequence of commands and what you should
see happen. Run it after `docker compose up --build -d` (see `DOCKER.md` first if you
haven't already).

## 1. A real kill, not a simulated one

```bash
docker attach quiver-node-a
```
```
create doc1
update doc1 before-the-kill
```
Detach with `Ctrl+P` `Ctrl+Q` (not `Ctrl+C` — see `DOCKER.md`).

```bash
docker kill -s SIGKILL quiver-node-a
```

This is the real thing `ChaosIntegrationTest.killedNodeRecoversEverythingItHadBeforeTheKill`
only approximates inside one JVM: no shutdown hook runs, nothing gets a chance to flush
anything, because `SIGKILL` cannot be caught or deferred by definition.

```bash
docker compose up -d node-a
docker attach quiver-node-a
```
```
status doc1
```

Expect `before-the-kill`, recovered from the journal before any peer could have resent
it — same property, same reason it holds (every append already fsynced before the kill),
now proven against an actual killed OS process instead of a JVM test's best imitation of
one.

## 2. A real network partition

A `ChaosProxy`-based test cuts a TCP connection at the application's own socket layer.
This cuts the actual network path between two containers, which is a meaningfully
different and stronger claim:

```bash
docker network disconnect quiver_quiver-net quiver-node-b
```

Node B is now unreachable from A and C at the network level — not refusing connections,
genuinely absent from the network, the way a real partition (a severed cable, a bad
router) behaves.

```bash
docker attach quiver-node-a
```
```
create doc1
update doc1 written-during-partition
```

Node B cannot receive this — there's no path for it to arrive on. Attach to node C and
confirm it *did* get the write (it was never partitioned):

```bash
docker attach quiver-node-c
```
```
status doc1
```

Heal the partition:

```bash
docker network connect quiver_quiver-net quiver-node-b
docker attach quiver-node-b
```
```
status doc1
```

It won't show up immediately — B reconnects to the network, but nothing automatically
re-triggers a sync. Request one:

```
list
```
If `doc1` isn't there yet, that's expected — B only asked its peers for a full sync once,
at its own startup, long before this write happened. In the CLI there's no "resync" command,
so the practical way to see it converge is to create a *new* write after reconnecting and
let normal broadcast traffic carry B's state forward, or simply restart node B
(`docker compose restart node-b`), which runs `requestFullSyncFromPeers()` again on
startup and will pull in everything it missed — exactly the recovery path
`ChaosClusterTest.isolatedNodeCatchesUpOnceThePartitionHeals` proves at the JVM level.

## 3. A frozen peer, not a dead one

`docker kill` and `docker network disconnect` both produce an *absence* — nothing to
connect to, or nowhere for the packet to go. A frozen process is different: the TCP
connection itself can still be live while nothing on the other end is reading or
responding, which is what actually exercises `NodeServer`'s read timeout
(`SOCKET_TIMEOUT_MILLIS`, 5 seconds) rather than a connection-refused error.

```bash
docker pause quiver-node-b
```

This genuinely stops B's process (`SIGSTOP`, not something B's code can see or react
to) while its TCP connections and sockets stay bound. Try writing from A and watch A's
logs — you should see it attempt the connection, then time out after about 5 seconds
rather than fail instantly the way a disconnected peer does.

```bash
docker unpause quiver-node-b
```

B resumes exactly where it left off, mid-syscall, with no memory of having been frozen.

## What this does and doesn't prove

These three scenarios, plus the automated suite, cover: unclean process death, network
partition and healing, relay-through-a-third-node, message reordering, and sustained
garbage input — each exercised at the layer that can test it faithfully (JVM-level where
a proxy is enough, real Docker where it isn't). What's still out of scope for all of it:
multi-host network partitions (everything here runs on one Docker host), disk-level
corruption rather than a torn last line, and clock skew between nodes (every timestamp
in this project is wall-clock time from one machine in these tests).
