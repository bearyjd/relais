# Feature-09 — Auth-gated `GET /` web dashboard

> Branch: `feat/relais-web-dashboard`. Depends on **PR1** (JVM `src/test` harness). Reuses the
> existing metrics, bearer auth, and HTTP response machinery — **adds no new framework**.

## Summary
Relais exposes machine-readable status today (`GET /metrics` Prometheus/JSON, `GET /health`) but no
human page. This feature adds a **single, static, auth-gated `GET /` HTML page**: node status (live /
engine-ready), thermal state, decode tok/s, current model (+ a selector to switch it), and a short
recent-request log — all built by **reusing the metrics already assembled in `RelaisMetrics`**. The
page is a thin server-rendered string over a **pure status-payload assembly function** (the unit-test
seam); no client framework, no inline scripts, no new attack surface beyond one authenticated route.
It MUST follow `DESIGN.md`: amber signal-relay (`#FFB000`) on near-black (`#0B0B0D`), monospace,
broadcast-beacon mark, dark-only, label-left/value-right rows on hairline dividers.

## Context (verified against the code)

### Status data already exists — assemble, don't recompute
`RelaisMetrics.renderJson(context)` (`RelaisMetrics.kt:193-214`) already returns exactly the fields a
dashboard needs, as a `JSONObject`:
`uptime_seconds`, `model_id`, `backend`, `engine_ready`, `tokens_generated_total`, `errors_total`,
`shed_total`, `decode_tokens_per_second`, `inference_p50_seconds`, `inference_p95_seconds`,
`thermal_status`, `thermal_headroom`, `memory_rss_bytes`, `queue_depth`, `restarts_total`.
Live-readiness comes from `RelaisEngine.isReady` (`RelaisEngine.kt:151-152`) and thermal level from
`ThermalGovernor.statusValue` (`ThermalGovernor.kt:50`) — both already surfaced in `/health`
(`RelaisHttpServer.kt:242-249`). The **recent-request log** is the one piece not yet retained:
`RelaisMetrics.recordRequest` (`:61-64`) only keeps `"<endpoint> <status>" -> count` aggregates
(`requestCounts`, `:44`), so a small bounded ring buffer of recent `(endpoint, status, ts)` must be
added (see Design §2).

### Where the route plugs in (and what it must reuse)
- Router: the `when` block in `handle` (`RelaisHttpServer.kt:241-285`). Add a
  `method == "GET" && path == "/"` branch that calls `respondText(sock, 200, html, "text/html; …")`.
  `/metrics` already demonstrates the non-JSONObject `respondText` path (`:251-258`).
- Auth: every non-`/health` route is bearer-gated (`:225-239`) via `authorized(header)`
  (`:409-413`, constant-time `MessageDigest.isEqual` against `RelaisConfig.apiKey`). The dashboard
  **must sit behind this same gate** — i.e. it must NOT be added to the `/health`-style open carve-out.
  Browser caveat (document in PR): a browser can't set `Authorization: Bearer …` on a plain
  navigation; the operator opens the page with a tool that can (curl/extension) or the page is fetched
  by an authed client. Do **not** weaken auth to make a bare browser GET work; keeping it bearer-gated
  is the security requirement (see Risks).
- Model selector: `RelaisConfig.modelId(context)` / `RelaisConfig.setModelId(context, value)`
  (`RelaisConfig.kt:133-152`). `setModelId` already invalidates the cached path and stale ref on
  change. A selector POST reuses this; the **default/canonical id** is
  `RelaisConfig.DEFAULT_MODEL_ID = "litert-community/gemma-4-E4B-it-litert-lm"` (`:49`).

### Security headers: none today — add them with this static HTML route
`respondText` (`RelaisHttpServer.kt:453-469`) emits only `Content-Type`, `Content-Length`,
`Connection: close`, plus the caller's `extraHeaders` list (the same seam shed uses for `Retry-After`,
`:296-306`). There is **no** `Content-Security-Policy` / `X-Frame-Options` / `X-Content-Type-Options`
anywhere (grep-confirmed). Because this is the first route that returns HTML, ship it with a strict
header set via `extraHeaders` so we don't widen attack surface:
`Content-Security-Policy: default-src 'none'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'`,
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`.
No external origins, **no `script-src` at all** (the page is scriptless — see Design §3).

## Design

### 1. Pure status-payload assembly function (the unit-testable seam)
A standalone function in a new file
`Android/src/app/src/main/java/cc/grepon/relais/RelaisDashboard.kt`. It takes **plain inputs**
(already-collected status values), returns an immutable `data class`, and touches **no `Context`,
no sockets, no Android**:

```kotlin
package cc.grepon.relais

/** One row in the recent-request log (already-normalized endpoint label + status + age). */
data class RequestLogEntry(val endpoint: String, val status: Int, val ageSeconds: Long)

/** Everything the dashboard renders, assembled from metrics already collected elsewhere. */
data class DashboardStatus(
  val live: Boolean,            // engineReady && running
  val statusLabel: String,      // "LIVE" | "STARTING" | "OFFLINE"  (DESIGN.md status mapping)
  val thermalLabel: String,     // human thermal level ("NONE".."SHUTDOWN")
  val decodeTokensPerSec: Double,
  val currentModelId: String,
  val uptimeSeconds: Double,
  val queueDepth: Int,
  val errorsTotal: Long,
  val shedTotal: Long,
  val recentRequests: List<RequestLogEntry>,
)

/**
 * Pure assembler: maps raw status inputs to the render model. statusLabel follows DESIGN.md
 * (LIVE = ready+running, STARTING = startupInProgress, OFFLINE otherwise). No I/O, no Context.
 */
fun assembleDashboardStatus(
  engineReady: Boolean,
  startupInProgress: Boolean,
  thermalStatus: Int,
  decodeTokensPerSec: Double,
  currentModelId: String,
  uptimeSeconds: Double,
  queueDepth: Int,
  errorsTotal: Long,
  shedTotal: Long,
  recentRequests: List<RequestLogEntry>,
): DashboardStatus { /* … pure mapping … */ }

/** Maps Android THERMAL_STATUS_* (0..6) to a label; out-of-range -> "UNKNOWN". Pure. */
internal fun thermalLabel(status: Int): String

/** Renders [DashboardStatus] to the HTML string. Thin; DESIGN.md tokens inlined as a <style>. */
fun renderDashboardHtml(status: DashboardStatus): String
```

`assembleDashboardStatus` + `thermalLabel` are the tested seams. `renderDashboardHtml` is a thin
string builder over the data class; the HTTP branch wires real values in (from
`RelaisMetrics.renderJson` / `RelaisEngine` / `ThermalGovernor` / `RelaisConfig`).

### 2. Recent-request log (bounded, in `RelaisMetrics`)
Add a small fixed-size ring buffer (e.g. last 20) of `RequestLogEntry`-shaped records, appended in
`RelaisMetrics.recordRequest` (`RelaisMetrics.kt:61-64`) using the **already-normalized** endpoint
label (security M6 — `recordRequest` is called with `endpointLabel(path)`, `RelaisHttpServer.kt:194`,
so no raw path / IP / key ever enters the log). Expose a `fun recentRequests(): List<…>` snapshot
(synchronized, bounded) for the assembler. **Never** log request bodies, IPs, the API key, the HF
token, or filesystem paths — same constraint as the existing `RelaisMetricsLeakTest` posture
(`RelaisMetrics.kt:38`).

### 3. Render: scriptless, DESIGN.md-faithful
- **No JavaScript** — the only interactive element is the model selector, implemented as a plain
  `<form method="POST" action="/select-model">` with a `<select>` of allowlisted ids + a submit
  button. This keeps `script-src 'none'` honest and avoids the entire XSS class for a dynamic page.
- **Selector POST:** add `method == "POST" && path == "/select-model"` (bearer-gated, like everything
  else) that reads the chosen id, **validates it against the model allowlist/catalog** (reuse the
  existing curated catalog used by the selector — see `relais-node-model-selector.plan.md`), and on
  match calls `RelaisConfig.setModelId`. Reject unknown ids with `400` (never persist an unvalidated
  id). Respond `303 See Other` back to `/` (or `200` + the re-rendered page).
- **Escaping:** any value interpolated into HTML (model id, endpoint labels) goes through an HTML
  escaper (`& < > " '`). `RelaisMetrics` already has a Prometheus `esc` (`:101-103`) — add an HTML
  analog in `RelaisDashboard.kt`; do not reuse the Prometheus one (different rules). Unit-test it.
- **DESIGN.md tokens** inlined in one `<style>` block: bg `#0B0B0D`, surface `#16171A`, hairline
  `#2A2B30`, paper text `#EDEAE3`, muted `#8A8780`, accent amber `#FFB000`, stop `#FF5247` (Stop only);
  `font-family: monospace`; wordmark `RELAIS` uppercase bold letter-spacing ~5sp amber; status dot
  amber (full when LIVE, 60% STARTING, muted OFFLINE); label-left / value-right rows on hairline
  dividers; crisp 6px button radius; dark-only. The pulsing-dot motion (`DESIGN.md` Motion) is a CSS
  `@keyframes` (alpha 0.3→1.0 ~900ms reverse) gated to LIVE — pure CSS, no JS, so it doesn't violate
  `script-src 'none'`.

## TDD plan (pure JVM, no Robolectric — `src/test`)
New file `Android/src/app/src/test/java/cc/grepon/relais/RelaisDashboardTest.kt`, JUnit4, mirroring
`RelaisEngineConfigTest` (pure functions, no `Context`).

1. **status-label mapping (RED→GREEN):**
   `assembleDashboardStatus(engineReady = true, startupInProgress = false, …).statusLabel == "LIVE"`
   and `.live == true`; `engineReady = false, startupInProgress = true` → `"STARTING"`, `live == false`;
   `engineReady = false, startupInProgress = false` → `"OFFLINE"`.
2. **thermal label mapping:** `thermalLabel(0) == "NONE"`, mid value → its label, `thermalLabel(99)`
   (out of range) → `"UNKNOWN"` (no crash on an unexpected int).
3. **field pass-through:** decode tok/s, current model id, uptime, queue depth, errors/shed totals,
   and the recent-request list arrive intact on the `DashboardStatus` (assembler doesn't drop/reorder).
4. **HTML escaping:** the HTML escaper turns a crafted model id like `a"/><script>x</script>` into a
   fully escaped string (no raw `<`, `>`, `"`); assert `renderDashboardHtml` output contains no
   unescaped `<script` for any field. (XSS-injection guard at the render seam.)
5. **render contains the right surfaces (thin smoke):** `renderDashboardHtml(sample)` contains the
   `RELAIS` wordmark, the amber accent `#FFB000`, the current model id (escaped), the `LIVE/STARTING/
   OFFLINE` label, and a `<form … action="/select-model" method="POST">` — and contains **no**
   `<script` tag at all (scriptless invariant).

RED first: tests reference `assembleDashboardStatus` / `thermalLabel` / `renderDashboardHtml` that
don't exist yet (compile-fail = RED). GREEN: implement `RelaisDashboard.kt` + the ring buffer; all
pass; existing ported suites still pass.

## Acceptance criteria
- New `src/test` tests cover status-label mapping, thermal-label mapping, field pass-through, HTML
  escaping, and the scriptless/DESIGN-token render smoke; each new behavior failed before and passes
  after.
- `GET /` returns the dashboard **only when bearer-authed** (401 without the key, same gate as
  `/metrics`); `/select-model` is bearer-gated, validates the id against the allowlist, and rejects
  unknown ids with `400` without persisting them.
- Page reuses `RelaisMetrics`/`RelaisEngine`/`ThermalGovernor`/`RelaisConfig` data — no duplicated
  status computation — and the recent-request log carries no IP / path / key / token / FS path.
- Response carries the strict security headers (`CSP default-src 'none'` + no `script-src`,
  `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`); the page is scriptless.
- UI matches `DESIGN.md` (amber `#FFB000` on `#0B0B0D`, monospace, `RELAIS` wordmark, beacon dot,
  label/value rows, dark-only) — flagged in review against `DESIGN.md`.
- `./gradlew testDebugUnitTest` (work dir `Android/src`) **green**; `./gradlew assembleRelease` **green**.
- CI green; independent code review APPROVE on the **final** diff (green CI ≠ reviewed); UI/design
  review against `DESIGN.md`.
- Merge to `main` only after the above.

## Risks & open questions (call out in PR)
- **Don't widen attack surface:** the page is static + bearer-gated + scriptless. The only state
  change is `/select-model`, which must validate against the allowlist and reuse the existing
  constant-time auth — no new unauthenticated route, no inline JS, no external origins.
- **Browser-auth ergonomics:** a plain browser navigation can't send `Authorization: Bearer`. Options
  (decide in PR, do not weaken auth): (a) document curl/extension usage; (b) accept the key as a
  one-shot `?` param only over the LAN-HTTPS binding (NOT loopback HTTP) and immediately advise
  rotating — riskier, likely **rejected**; (c) leave it API-client-only for v1. Default to (c)/(a).
- **CSRF on `/select-model`:** it's a state-changing POST behind bearer auth on a LAN node; still add
  a basic same-origin/`Sec-Fetch` check or a form token if (b) is ever chosen, since a bearer in a
  URL + a `<form>` invites CSRF. Out of scope if we stay API-client-only.
- **Log retention bound:** ring buffer fixed at ~20 entries; memory is O(1), no unbounded growth
  (mirrors the rate-limiter's bounded-map discipline, `RelaisHttpServer.kt:57-83`).

## Guardrails / PR
- Branch `feat/relais-web-dashboard`; PR to `main`; review the final diff; merge on green CI.
- Tiny + dependency-free: one new pure file (`RelaisDashboard.kt`) + a bounded log buffer in
  `RelaisMetrics` + thin `GET /` / `POST /select-model` branches in `RelaisHttpServer`. No new
  framework, no JS, no external assets. **Do not touch the AICore path** (out of scope here).
- MUST follow `DESIGN.md` — read it before any visual decision; QA flags any deviation.

## Deferred on-device gate (document in PR, do NOT fake)
On a real device (live node = Pixel 9 "comet"), authed-fetch `GET /` and confirm: status reflects the
running model, thermal label tracks `ThermalGovernor`, decode tok/s updates after a request, the
recent-request log fills, and a `/select-model` POST to a valid allowlisted id switches the served
model (and an invalid id is rejected `400`). Owner runs later; do not claim it as done in the PR.
