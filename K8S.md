# Running Quiver on Kubernetes

## Why a Deployment per node, not a StatefulSet

Quiver's cluster membership is static and config-driven — `config.json` lists exactly
who's in the cluster, and there's no join/leave protocol (see README's "Known
limitations"). A StatefulSet's defining feature is giving interchangeable, ordinal-
numbered replicas (`quiver-0`, `quiver-1`, ...) stable identity as the set scales up
and down. That doesn't fit here: node A is not "replica 0 of a scalable set," it's a
specific, named peer that every other node's `config.json` entry points at by name.
Three separate single-replica Deployments — one per named node — map onto what the
project actually is today. A StatefulSet would be the right tool if and when dynamic
membership gets built; it isn't yet.

## Prerequisites

- Docker Desktop with Kubernetes enabled: **Settings → Kubernetes → Enable
  Kubernetes**, then wait for the green "Kubernetes is running" status. This gives you
  a real local cluster that **shares Docker Desktop's own image cache** — the
  `quiver:latest` image you already built for Compose is immediately usable here with
  no registry push.
- `kubectl` (installed automatically alongside Docker Desktop's Kubernetes feature).

## Critical detail: `imagePullPolicy: Never`

Kubernetes' default pull behavior for a `:latest`-tagged image is to pull from a
registry — which would fail immediately, since `quiver:latest` only exists on your
machine. Every manifest here sets `imagePullPolicy: Never` explicitly so Kubernetes
uses the local image Docker Desktop already has, instead of trying to reach Docker Hub
and failing with `ImagePullBackOff`. If you ever see that error, this is the first
thing to check.

## Deploy

```bash
docker compose build        # make sure quiver:latest is current
kubectl apply -k .
```

Run this from the project root. `-k` (not `-f`) tells kubectl to process the
`kustomization.yaml` there, which does three things in one command: creates the `quiver` namespace, generates the `quiver-config`
ConfigMap directly from your own `docker/config.json` (not a second, separately
authored copy of it — and the reason that file sits at the project root rather than
inside `k8s/` is explained in its own comments), and creates all three
nodes' PVC/Deployment/Service.

Check everything came up:

```bash
kubectl get pods -n quiver
```

You should see `quiver-node-a-...`, `quiver-node-b-...`, `quiver-node-c-...`, each
`1/1 Running` once their readiness probe passes (a few seconds). The probe is a TCP
connect to each node's peer port (9001): it succeeds only once the JVM has started,
config and identity resolution finished, and `NodeServer` is actually accepting
connections — not merely once the container process exists.

**Why not probe `/metrics`?** It would be a richer signal, and the observability round
already built the endpoint. But kubelet probes connect to the pod's IP from outside the
container, and I couldn't verify whether `MetricsHttpServer` binds all interfaces or
only loopback. If it's loopback-only, an HTTP probe fails every time and Kubernetes
restarts the pod in a loop. The peer port is already proven reachable over the network
(that's how the Compose nodes talk to each other). To upgrade: from inside a running
pod, check that `/metrics` answers on the pod IP (`kubectl exec <pod> -n quiver --
sh -c "wget -qO- http://\$(hostname -i):10001/metrics | head"`), and if it does, swap the
probes in `k8s/node-*.yaml` to `httpGet: { path: /metrics, port: metrics }`.

## Interacting with a node

Unlike `docker attach <container-name>`, `kubectl attach` needs the pod's exact
(randomly-suffixed) name:

```bash
kubectl get pods -n quiver                      # copy a pod name, e.g. quiver-node-a-7d9f8c6b5-xk2pl
kubectl attach -it <pod-name> -n quiver
```

You'll land on the familiar `quiver>` prompt. Detach the same way as Docker — **Ctrl+P,
Ctrl+Q** — without stopping the node (`kubectl attach`'s default detach sequence is
the same one Docker uses).

## Try it: watch Kubernetes do what the chaos round proved works

This is the real payoff of this round. The chaos-testing suite already proved Quiver's
journal survives a process that's killed outright — no graceful shutdown, nothing
closed cleanly (see `ChaosIntegrationTest` and `CHAOS.md`). Kubernetes restarting a
failed pod is exactly that scenario, for real, driven by an orchestrator instead of a
test harness:

```bash
kubectl attach -it <node-a-pod-name> -n quiver
create doc1
update doc1 hello-from-kubernetes
# Ctrl+P, Ctrl+Q to detach

kubectl delete pod <node-a-pod-name> -n quiver
```

The Deployment controller notices immediately and starts a fresh pod — no `kubectl`
command tells it to, that's the controller doing its job. Watch it come back:

```bash
kubectl get pods -n quiver --watch     # Ctrl+C once the new node-a pod shows 1/1 Running
kubectl attach -it <new-pod-name> -n quiver
status doc1
```

`doc1` should show `hello-from-kubernetes` immediately — recovered from the
`PersistentVolumeClaim`-backed journal, the same way it recovered in every earlier
local, Docker, and chaos-test restart this project has already proven, now with an
orchestrator doing the killing and restarting instead of a terminal command or a test.

## Cleaning up

```bash
kubectl delete -k .
```

Removes everything the kustomization created, PVCs included — this deletes the
journals too. There's no equivalent of Compose's "keep the volumes" default here;
`kubectl delete -k` is closer to `docker compose down -v`.

## What this doesn't do (same honesty as every other round)

- **No Service of type LoadBalancer or Ingress.** Nothing here is reachable from
  outside the cluster — on purpose, since there's nothing this project needs exposed
  externally yet (the CLI is accessed via `kubectl attach`, not a network port a
  browser would hit).
- **No Horizontal Pod Autoscaler.** Scaling a *named* node doesn't mean anything for
  this project, per the StatefulSet discussion above — there's nothing here an HPA
  would be scaling correctly.
- **No Prometheus Operator / ServiceMonitor.** Each Service already exposes a
  `metrics` port pointing at the real `/metrics` endpoint from the observability
  round; wiring an actual Prometheus instance to scrape it across the cluster is a
  natural next step, not done here.
- **Single-node cluster only.** Docker Desktop's Kubernetes is one node pretending to
  be a cluster. These manifests would need real anti-affinity rules to guarantee the
  three pods land on genuinely different machines in a multi-node cluster — untested
  here, since nothing in this sandbox could reach one.
