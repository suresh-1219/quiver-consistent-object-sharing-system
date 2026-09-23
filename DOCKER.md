# Running Quiver with Docker

## Prerequisites

Docker Desktop (Windows/Mac) or Docker Engine (Linux). If you don't have it:
- Windows/Mac: install Docker Desktop from docker.com, launch it once, wait for it to
  say "Docker Desktop is running" before continuing.

## File placement

Copy these four files into the root of your project (the folder containing `pom.xml`),
preserving the `docker/` subfolder:

```
quiver/
├── pom.xml
├── docker-compose.yml       <- new
├── .dockerignore            <- new
└── docker/
    ├── Dockerfile           <- new
    └── config.json          <- new
```

## Build and start the cluster

```
docker compose up --build -d
```

`--build` forces a fresh image build the first time (or after a code change);
`-d` runs the containers in the background instead of tying up your terminal.

Check they're all up:

```
docker compose ps
```

You should see `quiver-node-a`, `quiver-node-b`, `quiver-node-c` all "Up".

## Interacting with a node

Each node's CLI loop is tied to its container's stdin, so you attach to it like a
terminal rather than running one-off commands:

```
docker attach quiver-node-a
```

You'll land on the `quiver>` prompt exactly like the local run. Try the same sequence
we ran locally:

```
create doc1
update doc1 hello-from-A
```

**To detach without stopping the node**, press `Ctrl+P` then `Ctrl+Q` (not Ctrl+C —
that sends SIGINT, which triggers the shutdown hook and stops the node, same as typing
`exit`). This detach sequence is a Docker convention, not something Quiver defines.

Open a second terminal and attach to another node to watch sync happen live:

```
docker attach quiver-node-b
status doc1
```

## Testing persistence

```
docker attach quiver-node-a
exit
```

This stops node A's container (the shutdown hook flushes its journal, same as the
local demo). Bring it back:

```
docker compose start node-a
docker attach quiver-node-a
status doc1
```

The named volume (`node-a-data`) keeps the journal file across container restarts, so
this should behave identically to the local restart test — the value comes back
immediately, before any peer sync could answer.

## Stopping everything

```
docker compose down
```

Stops and removes the containers, but **keeps the named volumes** (so journals
survive). To wipe persisted data too:

```
docker compose down -v
```

## Rebuilding after a code change

```
docker compose up --build -d
```

Docker's layer caching means this only re-runs `mvn package` (not the dependency
download) if only your `src/` changed.
