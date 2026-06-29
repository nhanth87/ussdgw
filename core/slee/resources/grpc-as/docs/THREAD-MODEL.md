# USSD Gateway Thread Model — SRE / DevOps Guide (THREAD-1)

> **Audience:** SRE / DevOps / capacity planning. Read this **before** sizing WildFly
> thread pools or pushing to production with a 10k TPS SLA.

## 1. The four thread pools you need to know

The gRPC AS Resource Adaptor touches **four** distinct thread pools. Mis-sizing any of them
will silently cap throughput or cause OOM.

```
┌─────────────────────────────────────────────────────────────────────────┐
│  AS (Application Server)                                               │
│   │ HTTP/2 stream over TLS (port 8443) or plaintext (port 8453)         │
│   ▼                                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Netty Event Loop (default 2*CPU)                                │   │
│  │  • Read/write bytes                                              │   │
│  │  • TLS handshake                                                 │   │
│  │  • gRPC frame parsing                                            │   │
│  └─────────────────────────────┬───────────────────────────────────┘   │
│                                ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  gRPC push executor pool — "ussd-grpc-push-*"                    │   │
│  │  • Decode envelope                                               │   │
│  │  • Build GrpcPushActivityImpl                                    │   │
│  │  • Call sleeEndpoint.fireEvent()  ← YIELDS to SLEE               │   │
│  └─────────────────────────────┬───────────────────────────────────┘   │
└────────────────────────────────┼─────────────────────────────────────┘
                                 ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │  SLEE Event Router thread pool                                   │
  │  • Delivers GrpcPushReceivedEvent to SBBs                        │
  │  • Triggers timers (50ms poll)                                   │
  │  • Default: 16 threads, tunable via                              │
  │    -Dorg.mobicents.slee.event.router.thread.count=N              │
  └─────────────────────────────┬───────────────────────────────────┘
                                ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │  SLEE SBB thread (per SBB instance, pooled by SLEE container)    │
  │  • GrpcClientSbb.onTimer() — poll GrpcResponseRegistry           │
  │  • Calls HttpServletResourceAdaptor / Mapss7ResourceAdaptor etc. │
  └─────────────────────────────┬───────────────────────────────────┘
                                ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │  gRPC client executor pool (default: gRPC-managed, ~CPU*8)       │
  │  • asyncUnaryCall callbacks (onNext / onError / onCompleted)     │
  │  • Deposits into GrpcResponseRegistry                            │
  └─────────────────────────────────────────────────────────────────┘
```

## 2. Each pool, in detail

### 2.1 Netty Event Loop (`io.netty.eventLoopThreads`)

- **What it does:** All socket I/O and TLS. Created by grpc-netty-shaded.
- **Default size:** `2 * availableProcessors()`.
- **Bottleneck symptom:** if you see CPU pinning on a small number of threads, raise this.
- **Sizing for 10k TPS:** Default is fine. Netty handles 10k+ concurrent connections on
  2–4 cores without breaking a sweat.

### 2.2 gRPC push executor pool (`"ussd-grpc-push-*"`)

- **What it does:** Runs the gRPC server-side method handler (`onProcess`) — decode envelope,
  build SLEE activity, fire SLEE event.
- **Default size:** `max(8, CPU*4)`. Tunable via RA property `GRPC_PUSH_WORKER_THREADS`
  (or `grpcpushworkerthreads` via MBean).
- **Bottleneck symptom:** Push latency climbs but CPU is idle. Increase this.
- **Sizing for 10k TPS:** 32–64 threads recommended.
  ```
  GRPC_PUSH_WORKER_THREADS=64
  ```
- **Why not more?** This pool does NOT block on SLEE — `fireEvent()` returns immediately.
  But each invocation allocates ~1KB. 64 threads ≈ 10k TPS with 0.5ms per call.

### 2.3 SLEE Event Router thread pool

- **What it does:** Delivers events from RAs to SBBs; drives SBB timers.
- **Default size:** 16 (depends on JAIN SLEE container version).
- **Bottleneck symptom:** Push events queue up; SBBs are slow to receive events.
- **Sizing for 10k TPS:** raise to 32 minimum. Most SLEE containers accept
  `-Dorg.mobicents.slee.event.router.thread.count=32`.
- **Warning:** SLEE is single-threaded **per SBB instance**. If one SBB instance handles
  multiple sessions, they serialize.

### 2.4 gRPC client executor pool (gRPC-managed)

- **What it does:** Runs the gRPC `onNext / onError / onCompleted` callbacks when an AS
  response arrives or the deadline fires.
- **Default size:** Managed by gRPC itself; typically `max(2, CPU*8)`.
- **Bottleneck symptom:** AS responses arrive but `GrpcResponseRegistry.put()` is delayed.
  Increase this.
- **Sizing for 10k TPS:** Default is fine. Callbacks do very little work (~100µs).

## 3. The 50ms SLEE poll timer

The SBB registers a SLEE timer that polls `GrpcResponseRegistry` every **50ms**. This means:

- **Latency floor:** worst-case response delivery to SBB = 50ms + network + AS processing.
- **Timer thread usage:** one timer per active SBB instance. With 10k active sessions
  and a 50ms period, that's 200k timer fires/sec — SLEE handles this efficiently in a
  hierarchical timing wheel, but **monitor SBB instance count** to avoid runaway memory.

## 4. HTTP Servlet RA vs gRPC RA — what's different

| Pool | HTTP Servlet RA (legacy) | gRPC RA |
|---|---|---|
| Inbound push ingress | Undertow XNIO worker (blocks on `lock.wait()`) | Netty event loop → `ussd-grpc-push-*` pool |
| Outbound to AS | OkHttp dispatcher (sync, blocking) | gRPC executor (async, callback) |
| Push latency | Servlet thread blocked for entire AS call (not used in push) | ~5ms — non-blocking |
| Push throughput | Limited by servlet pool size | Limited by gRPC executor pool size |
| Failure on AS slow | Servlet thread tied up — pool exhaustion | Deadline triggers clean `onError` callback |

**Migration note:** When you flip the gateway from HTTP servlet push to gRPC push, your
"max concurrent push" metric will appear to *drop* on the servlet pool — that's expected.
The gRPC pool is doing the same work, but with much less thread consumption per request.

## 5. Recommended pool sizes for a 10k TPS deployment

| Pool | Recommended | Property |
|---|---|---|
| gRPC push workers | 64 | `GRPC_PUSH_WORKER_THREADS=64` |
| gRPC push max concurrent calls | 10000 | `GRPC_PUSH_MAX_CONCURRENT_CALLS=10000` |
| SLEE Event Router | 32 | `-Dorg.mobicents.slee.event.router.thread.count=32` |
| Netty Event Loop | default | `2*CPU` |
| gRPC client executor | default | managed by gRPC |
| SLEE SBB pool | 32+ | container-specific |
| WildFly IO workers | 32+ | `io-threads` in `standalone.xml` |
| WildFly task scheduler | 16+ | `task-keepalive` threads |

**Memory budget (rough):**
- Netty direct buffers: ~512MB at 10k concurrent connections
- gRPC push heap: ~64MB (with 64 worker threads)
- GrpcResponseRegistry: ~1GB worst case (1M entries × ~1KB)
- SLEE SBB heap: ~2GB (depends on session state)

## 6. Monitoring checklist

When you see push latency climbing, check in this order:

1. **Netty event loop** CPU — if pinned, scale horizontally
2. **`ussd-grpc-push-*` thread pool** — `jstack | grep ussd-grpc-push | wc -l` should match config
3. **SLEE Event Router** — look for queued events in JMX
4. **gRPC client executor** — `jstack | grep grpc-default-executor`
5. **WildFly heap + GC** — if you see long GC pauses, raise heap or switch to G1GC

## 7. Common pitfalls

- ❌ **Setting `GRPC_PUSH_WORKER_THREADS` too high** (e.g. 512). This hurts context switching
     and burns memory. 64 is plenty for 10k TPS.
- ❌ **Lowering `GRPC_PUSH_MAX_CONCURRENT_CALLS`** thinking it reduces load. It does the
     opposite — it forces the AS to back off and re-connect, multiplying setup cost.
- ❌ **Disabling the SLEE poll timer** "to save CPU". The SBB will then never see responses.
- ❌ **Running a 50ms poll on 10k sessions** without checking timer wheel memory.
     Use SLEE's `HierarchicalTimingWheel` (default on most SLEE 2.x+) to keep this cheap.

## 8. References

- `GrpcPushServer.java` — `ussd-grpc-push-*` pool
- `GrpcAsResourceAdaptor.java` — gRPC client executor
- `GrpcResponseRegistry.java` — bounded capacity, background sweep thread
- `analysis_report.md` THREAD-1 — original finding