# Kubernetes round — what's new, and what is NOT verified

## Read this first: this round is less verified than any before it

Every earlier round was at least "reviewed by eye against the real source files." This
one has weaker footing, for two stated reasons:

1. **My sandbox was reset** between rounds, so the working copy of your project is gone
   and I couldn't re-read `Main.java`, `MetricsHttpServer.java`, or `docker/config.json`.
   I worked from the interface contract established earlier in this session (CLI flags,
   ports, the `quiver-data` journal path, the Dockerfile's `ENTRYPOINT`) rather than
   the files themselves. I tried to fetch the real `docker/config.json` from your repo
   so I wouldn't have to guess; the lookup didn't find it, so I designed around needing
   it instead (see "The config design" below).
2. **Nothing here has touched a Kubernetes cluster.** No `kubectl`, no cluster, no
   network in this sandbox. I validated YAML syntax and cross-references (selectors vs.
   labels, PVC claims, Service-to-pod matching) with a script, which catches typos —
   not whether the cluster will accept and run it.

So the honest expectation is: **something may need a small fix on first apply.** Two
already did, before packaging — worth knowing how they were caught:

- `kustomization.yaml` originally sat in `k8s/` and read `../docker/config.json`.
  Kustomize refuses files outside its own directory tree, and `kubectl` has no flag to
  relax that, so it would have failed at apply time. Moved to the project root.
- Health probes originally hit `/metrics`. Probes connect to the pod IP from outside the
  container; if `MetricsHttpServer` binds loopback only, every probe fails and
  Kubernetes restart-loops the pod. I couldn't verify the binding, so probes are now a
  TCP connect to the peer port (proven reachable across containers by the Compose
  demo). K8S.md has the exact command to check and the upgrade path.

## What's new (all new files — no existing source changed)

- `kustomization.yaml` (project root) — one `kubectl apply -k .` creates everything
- `k8s/namespace.yaml`
- `k8s/node-a.yaml`, `node-b.yaml`, `node-c.yaml` — each a PVC + Deployment + Service
- `K8S.md` — guide, including the "delete a pod and watch the journal recover it" demo

## The config design

The `quiver-config` ConfigMap is **generated from your own `docker/config.json`** by
kustomize, not written out in a YAML I authored. That file holds the Ed25519 public
keys; a second hand-maintained copy could drift or simply be wrong, and a wrong key
fails *silently* (peers reject each other's signatures). Services are named `node-a`,
`node-b`, `node-c` — identical to the Compose service names — so that file works
unedited, because cluster DNS resolves those names the way Compose's does.

## Other design choices to push back on if you disagree

- **Deployment per node, not a StatefulSet** — membership is static and named; see
  K8S.md for the reasoning. Revisit if dynamic membership is ever built.
- **`imagePullPolicy: Never`** — required, or Kubernetes tries to pull `quiver:latest`
  from a registry and fails with `ImagePullBackOff`. It also means this only works on a
  cluster that can see your local image (Docker Desktop's can).
- **`stdin: true` / `tty: true`** — same reason as Compose: the CLI loop and the server
  share a process, so closed stdin shuts the node down.
- **Known sharp edge:** `kubectl attach` detach (Ctrl+P, Ctrl+Q) behavior is from my
  knowledge of kubectl, untested here. If it doesn't detach, leave that terminal open
  and use another for other nodes.

## Where it most likely needs a fix

1. Pods stuck `ImagePullBackOff` → image not visible to the cluster; check
   `docker images quiver`.
2. Pods `CrashLoopBackOff` → `kubectl logs <pod> -n quiver` — most likely config path or
   key mismatch warning text.
3. Pods `Running` but `0/1` → readiness probe failing; check the peer port.
4. PVC `Pending` → no default StorageClass (Docker Desktop normally has one).
