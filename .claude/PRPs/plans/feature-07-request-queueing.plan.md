# Feature-07 — Bounded FIFO admission queue in front of the resident engine

> Branch: `feat/relais-request-queue`. Depends on **PR1** (JVM `src/test` harness). Composes with
> the existing thermal `503` shed; this adds the *concurrency* backpressure that shed does not cover.

## Summary
Relais runs a **single resident engine** and serializes every inference under one lock
(`RelaisEngine.generate` → `synchronized(lock)`, `RelaisEngine.kt:290`). The HTTP front end, however,
hands every accepted connection to a **16-thread pool** (`MAX_CONNECTIONS = 16`,
`RelaisHttpServer.kt:50,109`) with no admission control. Today, when N concurrent LAN clients hit
`/generate` or `/v1/chat/completions`, up to 16 worker threads enter `generate`, all increment the
in-flight gauge, and then **block on the engine lock**, each holding a worker thread and a client
socket for the full serialized wait (each decode can run up to the 120s inference timeout —
`RelaisEngine.kt:343`). The pool saturates, later-arriving requests get no worker at all (they sit in
the unbounded `ServerSocket` backlog), and clients see opaque hangs instead of a fast, honest signal.

This feature puts a **bounded FIFO admission queue** in front of the engine: inference requests are
admitted up to a fixed depth and **rejected fast with `429 + Retry-After`** when the queue is full,
so concurrent clients no longer silently race and hang the worker pool. The accept/reject decision is
a **pure function** (queue depth, capacity → decision + Retry-After) that is unit-tested in isolation;
the HTTP layer is a thin caller. This is admission control for *load*, orthogonal to the existing
thermal `503` for *heat* — both can fire, and the order between them is specified below.

## Context (verified against the code)

### Where serialization already happens (the bottleneck is real)
- `RelaisEngine.generate` (`RelaisEngine.kt:262-358`) calls `RelaisMetrics.incInFlight()` at the very
  top (`:268`) — **before** taking the lock — then does the actual decode inside
  `synchronized(lock) { … }` (`:290-353`). So `relais_queue_depth` (`RelaisMetrics.kt:178-180`,
  `inFlight.get()`) already counts *queued + running*, but nothing bounds how many threads pile onto
  that lock. The comment at `RelaisHttpServer.kt:50` even notes "single-engine node serializes anyway"
  — that is exactly the unbounded-wait this PRP fixes.
- Worker pool: `Executors.newFixedThreadPool(MAX_CONNECTIONS)` (`RelaisHttpServer.kt:109`), fed by
  `pool.execute { handle(client) }` in the accept loop (`:125`). `soTimeout = SOCKET_TIMEOUT_MS`
  (15s, `:49,187`) bounds *idle/slow read*, NOT time spent blocked on the engine lock.

### The two inference entry points that must go through admission
- `POST /generate` (`RelaisHttpServer.kt:260-277`) — guarded today only by `shedIfHot(::reply)` (`:261`).
- `POST /v1/chat/completions` (`:279-282`) — guarded today only by `shedIfHot(::reply)` (`:280`),
  then `handleOpenAi` (`:310-365`), which has **both** a non-streaming path (`:316-330`) and a
  streaming SSE path that commits a `200` header before decoding (`:336-342`). **Admission must be
  decided BEFORE the SSE `200` header is written** — once committed we can only emit an SSE error
  event, never a clean `429` (the post-commit hazard is already called out at `:333-335`).
- `/health` (`:242-249`), `/metrics` (`:251-258`) are **not** inference and must NOT be queued.

### Existing reject patterns to mirror (do not invent new shapes)
- `429` already exists for rate limiting (`:231-233`) — JSON `{"error": …}` body.
- `503 + Retry-After` shed (`shedIfHot`, `:296-306`): records `RelaisMetrics.recordShed()`, computes
  `ThermalGovernor.retryAfterSeconds() + (0..4).random()` jitter, and passes the header via the
  `extraHeaders` list to `reply(...)` → `respond(...)` → `respondText(...)` (`:449-469`). The
  `extraHeaders` mechanism is the seam for emitting `Retry-After` on the new `429` too.
- `reason()` (`:436-447`) already maps `429 -> "Too Many Requests"` and `503 -> "Service Unavailable"`.

## Design

### 1. Pure admission-decision function (the unit-testable seam)
A standalone, **context-free, hardware-free** function in a new file
`Android/src/app/src/main/java/cc/grepon/relais/RelaisAdmission.kt`:

```kotlin
package cc.grepon.relais

/** Outcome of an admission decision: accept into the queue, or reject with a Retry-After hint. */
sealed interface AdmissionDecision {
  data object Accept : AdmissionDecision
  data class Reject(val retryAfterSeconds: Int) : AdmissionDecision
}

/**
 * Pure admission policy for the single-engine FIFO queue. [queuedOrRunning] is the current
 * in-flight count (queued + running, == RelaisMetrics queue_depth); [capacity] is the hard cap.
 * Accept iff strictly under capacity, else reject. The Retry-After estimate scales with how far
 * over the line we are so a deep backlog backs callers off longer (anti-stampede).
 */
fun admit(
  queuedOrRunning: Int,
  capacity: Int,
  estimatedServiceSeconds: Int = DEFAULT_SERVICE_SECONDS,
): AdmissionDecision =
  if (queuedOrRunning < capacity) AdmissionDecision.Accept
  else AdmissionDecision.Reject(
    retryAfterSeconds = (estimatedServiceSeconds * (queuedOrRunning - capacity + 1))
      .coerceIn(MIN_RETRY_AFTER, MAX_RETRY_AFTER)
  )

const val QUEUE_CAPACITY = 8         // < MAX_CONNECTIONS (16): leave headroom for /health + /metrics
const val DEFAULT_SERVICE_SECONDS = 8 // coarse per-request decode estimate; tune on-device
const val MIN_RETRY_AFTER = 2
const val MAX_RETRY_AFTER = 30
```

Rationale for `QUEUE_CAPACITY < MAX_CONNECTIONS`: the pool stays able to answer the unqueued
endpoints (`/health`, `/metrics`) and to *serve the `429` itself* even when the engine is fully
backlogged. The function takes no `Context`, no sockets, no `RelaisEngine` — it is trivially testable.

### 2. Wiring (thin, in `RelaisHttpServer`)
- Add a process-wide gate keyed off the existing in-flight counter. Two viable seams — pick the
  smaller at implementation time and document the choice in the PR:
  - **(A) Decision at the HTTP boundary (preferred):** before entering either inference branch, read
    the current depth from a single source of truth and call `admit(depth, QUEUE_CAPACITY)`. On
    `Reject`, emit `429 + Retry-After: n` (jittered like shed) and return; on `Accept`, proceed into
    `RelaisEngine.generate` as today. To make the depth a true admission count (and not merely
    observe the post-hoc gauge), increment/decrement a dedicated `Semaphore(QUEUE_CAPACITY)`-style
    counter around the inference call, OR `tryAcquire()` a `Semaphore` whose permits == `QUEUE_CAPACITY`.
    A non-blocking `Semaphore.tryAcquire()` *is* the runtime embodiment of the pure `admit` decision;
    keep `admit` as the spec/tested policy and assert in a test that `tryAcquire` semantics match it.
  - **(B) Decision inside the engine:** have `generate` consult `admit` before `incInFlight()`. Riskier
    (engine is also reachable from the in-app HUD path); prefer (A) so the queue is an HTTP-front-end
    concern and the engine stays a pure serial executor.
- **Ordering with thermal shed (specified):** check **thermal `503` first** (device protection wins),
  then **admission `429`**. I.e. in each inference branch: `if (shedIfHot(::reply)) return` stays
  first (`:261`,`:280`); the admission check is added immediately after it. A hot device sheds before
  we bother queueing; a cool-but-saturated device returns `429`. Record a new
  `RelaisMetrics.recordQueueReject()` counter (mirror `recordShed()` at `RelaisMetrics.kt:66`) and
  expose `relais_queue_rejected_total` next to `relais_shed_total` (`:140-142`).
- **Streaming safety:** for `/v1/chat/completions`, the admission check must run inside `handleOpenAi`
  **before** the streaming `200` header write (`:336-342`) — emit the `429` while we can still set a
  clean status. Add the check at the top of `handleOpenAi`, before the `stream` branch.
- Release the permit in a `finally` that always runs (mirror the existing `finally` that calls
  `decInFlight()` at `RelaisEngine.kt:354-357`), so a thrown/timed-out request never leaks a slot.

### 3. Fairness / starvation (FIFO, bounded)
- A `java.util.concurrent.Semaphore(QUEUE_CAPACITY, /* fair = */ true)` gives FIFO admission among
  threads already past the gate, preventing a late request from starving an early one once both are
  admitted. Admission itself is non-blocking (`tryAcquire()` → `429` on miss), so a full queue never
  *blocks* a worker — it frees it immediately. This is the anti-starvation + anti-hang property.

## TDD plan (pure JVM, no Robolectric — `src/test`)
New file `Android/src/app/src/test/java/cc/grepon/relais/RelaisAdmissionTest.kt`, JUnit4, mirroring
`RelaisEngineConfigTest` style (pure functions, `assertEquals`/`assertTrue`, no `Context`).

1. **accept-under-capacity (RED→GREEN):** `admit(queuedOrRunning = 0, capacity = 8)` and
   `admit(7, 8)` both return `AdmissionDecision.Accept`. (Boundary: `capacity - 1` accepts.)
2. **reject-at-capacity:** `admit(8, 8)` and `admit(12, 8)` return `Reject`. (Boundary: exactly
   `capacity` rejects — strict `<`.)
3. **retry-after value:** `admit(8, 8)` → `retryAfterSeconds == DEFAULT_SERVICE_SECONDS` (one over);
   deeper backlog scales up and **clamps** at `MAX_RETRY_AFTER` (e.g. `admit(100, 8)` → `30`); never
   below `MIN_RETRY_AFTER`. Assert monotonic-nondecreasing in depth.
4. **interaction with thermal shed (ordering):** a small pure helper
   `decideBackpressure(shed: Boolean, queuedOrRunning: Int, capacity: Int): BackpressureOutcome`
   (extract the HTTP ordering into a testable function returning `Shed503 | QueueReject429 | Admit`).
   Assert: `shed = true` → `Shed503` **regardless** of queue depth (heat wins); `shed = false` +
   over capacity → `QueueReject429`; `shed = false` + under capacity → `Admit`.
5. **semaphore matches policy (property check):** with `Semaphore(8, fair=true)`, draining all 8
   permits then a 9th `tryAcquire()` returns `false` — i.e. the runtime gate rejects exactly when
   `admit` says `Reject`. (Pure JVM; no Android.)

RED first: tests 1–5 reference `admit` / `decideBackpressure` / `AdmissionDecision` that don't exist
yet (compile-fail = RED). GREEN: implement `RelaisAdmission.kt`; all pass; existing ported suites
(`RelaisEngineConfigTest`, `RelaisModelCatalogTest`, `RelaisHuggingFaceTest`) still pass.

## Acceptance criteria
- New `src/test` tests cover accept-under-capacity, reject-at-capacity, Retry-After value+clamp, and
  the thermal-shed-vs-queue ordering; each new behavior failed before the change and passes after.
- Both inference endpoints (`/generate`, `/v1/chat/completions` incl. streaming) go through admission;
  a full queue returns `429 + Retry-After` **fast** (no worker held on the engine lock for a rejected
  request) and the SSE `200` header is never committed for a rejected stream.
- Thermal `503` shed still fires and takes precedence over `429` (heat before load).
- New `relais_queue_rejected_total` counter exposed in `/metrics` alongside `relais_shed_total`;
  `relais_queue_depth` still reflects in-flight (queued + running).
- Permit/slot is always released (no leak on success, timeout, error, or client disconnect).
- `./gradlew testDebugUnitTest` (work dir `Android/src`) **green**; `./gradlew assembleRelease` **green**.
- CI green; independent code review APPROVE on the **final** diff (green CI ≠ reviewed).
- Merge to `main` only after the above.

## Risks & open questions (call out in PR)
- **Capacity & timeout tuning:** `QUEUE_CAPACITY = 8` and `DEFAULT_SERVICE_SECONDS = 8` are estimates;
  real per-request decode time depends on model + prompt length (decode can run to the 120s timeout,
  `RelaisEngine.kt:343`). Tune against measured `relais_inference_duration_seconds` p50/p95
  (`RelaisMetrics.kt:148-160,193-214`) on-device; the constants are the only thing to adjust, the
  policy function stays fixed.
- **Fairness vs throughput:** a *fair* semaphore guarantees no starvation but can marginally lower
  throughput vs unfair; acceptable for a single-engine node (serialization dominates anyway).
- **Queue-wait timeout:** admission is non-blocking (reject fast, don't block a worker), so there is
  no separate "max wait in queue" knob to mistune — the slot is held only for the duration of the
  actual serialized decode, already bounded by the 120s inference timeout. If a *bounded blocking*
  variant is ever wanted, add a `tryAcquire(timeout)` and a matching test; out of scope here.
- **Don't double-count:** the new counter must increment only on a real reject, and admitted requests
  must still flow through the existing `incInFlight`/`decInFlight` so `queue_depth` stays accurate.

## Guardrails / PR
- Branch `feat/relais-request-queue`; PR to `main`; review the final diff; merge on green CI.
- Keep the change minimal: one new pure file + thin wiring in `RelaisHttpServer` + one metric. No
  refactor of the engine or the pool. **Do not touch the AICore path** (`RelaisAicore`,
  `BackendSelector.aicoreAvailable`) — it is `false` on this hardware and out of scope.
- Follow `DESIGN.md` only insofar as any operator-visible string stays terse/machine-voiced; this
  feature ships no new UI.

## Deferred on-device gate (document in PR, do NOT fake)
On a real device (live node = Pixel 9 "comet"), fire ≥ `QUEUE_CAPACITY + 4` concurrent
`/v1/chat/completions` requests and confirm: excess requests get fast `429 + Retry-After` (not hangs),
the worker pool never wedges, `/health` and `/metrics` stay responsive throughout, and
`relais_queue_rejected_total` increments. Owner runs later; do not claim it as done in the PR.
