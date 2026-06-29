# gRPC Async Programming Model — USSD Gateway (GRPC-4)

> **Audience:** Application Server (AS) developers integrating with the USSD Gateway over gRPC.

## Why this document exists

The gRPC AS Resource Adaptor uses a **non-blocking, async** model that is fundamentally
different from the legacy HTTP/sync model that some AS teams may be familiar with. Misunderstanding
this model is the #1 cause of "session hangs", "timeouts" and "missing responses" in the field.
Please read this **before** tuning your AS or filing a bug.

## 1. HTTP/Sync (legacy) vs gRPC/Async — what changed

| Aspect | Legacy HTTP (sync) | New gRPC (async) |
|---|---|---|
| Servlet thread | blocks on `lock.wait()` until AS responds | NOT used; gRPC executor delivers response via callback |
| AS can take its time | yes — servlet waits | yes — but must respond **before `GRPC_DEADLINE_MS`** (default 30s) |
| Response path | `lock.notify()` → servlet resumes → SBB | gRPC executor calls `onNext()` → `GrpcResponseRegistry.put()` → SBB poll timer picks up |
| Where SBB picks up reply | immediate (servlet wakes up) | polled every **50ms** by a SLEE timer |
| AS errors | HTTP 5xx → exception in servlet | gRPC `onError()` → error response in registry |
| AS timeout | controlled by `HTTP_REQUEST_TIMEOUT` | controlled by `GRPC_DEADLINE_MS` (gRPC-level deadline) |

## 2. The async pipeline (sequence)

```
SBB (SLEE thread)
   │  submit(GrpcRequest)
   ▼
GrpcAsResourceAdaptor.submit()  ── instant return ──►  registry.place(correlationId, PENDING)
   │
   ▼  (gRPC executor, async)
ManagedChannel.newCall(Process, deadlineAfter(GRPC_DEADLINE_MS))
   │
   ▼  HTTP/2 stream to AS
   │
   ▼  (response arrives, OR deadline fires)
onNext / onError / onCompleted → registry.put(GrpcResponse.ok/error)
   │
   ▼  (SLEE poll timer, every 50ms)
GrpcClientSbb.onTimer() → registry.poll(correlationId)
```

## 3. The Response Registry — `GrpcResponseRegistry`

The registry is the single rendezvous point between the **gRPC executor thread** (producer)
and the **SLEE SBB thread** (consumer):

- Key: `correlationId` (== `sessionId` for pull/push correlation)
- Value: `GrpcResponse { ok | error, payload, requestId }`
- Bounded to **1,000,000 entries** with **FIFO eviction** (memory guard)
- Sweep runs on a **background thread** (NOT on the gRPC callback thread) — production-safe

If the AS never responds, the entry is **still consumed by the deadline**: gRPC invokes
`onError(DEADLINE_EXCEEDED)` after `GRPC_DEADLINE_MS`, and the SBB poll picks up the error.

## 4. The polling timer — 50ms

The SBB uses a SLEE timer to poll the registry every **50ms**. This is the worst-case latency
between AS response and SBB delivery.

- **Tunable** in your SBB code: the 50ms value is a guideline, not enforced by the framework
- Lower latency ⇒ higher CPU (poll loop is cheap but non-zero)
- 50ms is well under human-perceptible delay for USSD menus (< 100ms)

## 5. The deadline — `GRPC_DEADLINE_MS` (default 30000)

The gRPC client sets a per-call deadline of `GRPC_DEADLINE_MS` milliseconds. After this
elapses, gRPC **cancels the HTTP/2 stream** and delivers an error to `onError`.

**You MUST size your AS response time + a small network buffer under this deadline.**

| Deployment size | Recommended `GRPC_DEADLINE_MS` |
|---|---|
| Local AS (same DC, low-latency) | 5000 |
| Regional AS (same country) | 10000–15000 |
| Cross-region AS | 30000 (default) |
| Mobile-originated with async push | 30000 (must leave room for the async round-trip) |

**Warning:** if `GRPC_DEADLINE_MS` is shorter than your AS's typical response time, EVERY call
will appear to fail with `DEADLINE_EXCEEDED`. There is no retry — fix the config, do not raise
the value blindly (it holds gRPC executor threads).

## 6. AS Developer checklist

1. **Do not assume the SBB thread blocks.** Your handler can take seconds; the gRPC executor
   thread will be released. Just respond before `GRPC_DEADLINE_MS`.
2. **Use the same `correlationId` for pull & push.** The push (`push=true`) envelope must carry
   the `correlationId` (or `sessionId`) that the SBB is waiting on.
3. **Send a `GrpcPushReceivedEvent` response via the push server**, not a different channel.
   The SBB only listens to `ussd.grpc.events.PUSH_RECEIVED` on the local SLEE bus.
4. **Return errors as gRPC status**, not by silently closing the stream. Use `Status.INTERNAL`
   with a meaningful description; the gateway will surface it to the user via the
   `serverErrorMessage` property.
5. **Do not block inside gRPC handlers.** Use async/callback style — gRPC Netty is built on
   non-blocking I/O and blocking your handler throttles the whole executor pool.

## 7. Common pitfalls

- ❌ "My AS responded but the user never saw the menu" → most often **wrong `correlationId`** in
     the push envelope. The SBB poll loop times out and returns an error to the network.
- ❌ "I get DEADLINE_EXCEEDED on every call" → raise `GRPC_DEADLINE_MS` (default 30s is
     generous; only reduce if your AS is fast AND local).
- ❌ "Sometimes responses arrive 1–2 seconds late" → expected. 50ms poll + AS latency +
     network. This is fine for USSD.
- ❌ "I see 'empty-response' errors" → your AS returned an empty payload. Always send a
     non-empty response, even for menu navigation events.

## 8. References

- `GrpcAsResourceAdaptor.java` — `submit()` method
- `GrpcResponseRegistry.java` — registry + bounded capacity
- `GrpcClientSbb.java` — 50ms poll loop, deadline propagation
- `analysis_report.md` GRPC-4 — original finding