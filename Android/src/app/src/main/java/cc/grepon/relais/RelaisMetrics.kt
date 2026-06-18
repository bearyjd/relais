/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Process-wide observability for the node (Gate 3). Dependency-free; renders both Prometheus text
 * exposition (default) and JSON (for the in-app HUD).
 *
 * Counters are per-process-lifetime — standard for Prometheus, whose `rate()` handles resets. A
 * watchdog restart (new process) resets them, which is why [renderProm] also emits
 * `relais_process_start_time_seconds` (so scrapers see the restart) and a prefs-persisted
 * `relais_restarts_total`.
 *
 * Label hygiene (security M6): only the model *id* and backend name are exposed — never the model
 * filesystem path, the API key, the HF token, or any IP. See `RelaisMetricsLeakTest`.
 */
object RelaisMetrics {
  private val startMs = System.currentTimeMillis()

  // "<endpoint> <status>" -> count
  private val requestCounts = ConcurrentHashMap<String, AtomicLong>()
  private val tokensTotal = AtomicLong(0)
  private val shedTotal = AtomicLong(0)
  private val queueRejectedTotal = AtomicLong(0)
  private val errorsTotal = AtomicLong(0)
  private val inFlight = AtomicInteger(0)

  @Volatile private var lastDecodeTokS = 0.0
  @Volatile private var lastBackend = "none"

  // Session memory (Feature #5). `sessionHitsTotal` counts requests that loaded stored history; the
  // gauge holds the last-observed stored-turn count (refreshed by the record path + prune worker).
  // Security M6: fixed metric names, no labels — a session key or IP NEVER enters a metric.
  private val sessionHitsTotal = AtomicLong(0)
  @Volatile private var sessionTurnsGauge = 0L

  // Bounded ring buffer for the dashboard recent-request log (last 20 entries).
  // Security M6: only normalized endpoint labels enter here — never raw paths, IPs, keys, or FS paths.
  private const val REQUEST_LOG_CAPACITY = 20
  private val requestLog = ArrayDeque<RequestLogEntry>(REQUEST_LOG_CAPACITY)
  private val requestLogLock = Any()

  // Inference-latency histogram (seconds). Fixed buckets; quantiles computed at query time
  // (Prometheus convention) — precomputed p50/p95 live only in the JSON/HUD view.
  private val bucketBoundsSec = doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0, 120.0)
  private val bucketCounts = LongArray(bucketBoundsSec.size + 1) // last == +Inf
  private var latencyCount = 0L
  private var latencySum = 0.0
  private val histLock = Any()

  // Per-endpoint inference-latency histograms (Feature #10). Same bucket bounds as the global series
  // above, but keyed by a *normalized* endpoint label so `/generate` p95 separates from
  // `/v1/chat/completions` p95. Security M6: keys only ever enter via [endpointLabel], so label
  // cardinality is bounded to the fixed whitelist — no user-controlled path becomes a series.
  // The global (unlabeled) series stays the single, un-double-counted tail guarantee
  // ([recordLatency]); [recordEndpointLatency] writes ONLY here.
  private class EndpointHist {
    val counts = LongArray(bucketBoundsSec.size + 1) // last == +Inf
    var count = 0L
    var sum = 0.0
  }

  private val endpointHists = ConcurrentHashMap<String, EndpointHist>()
  private val endpointHistLock = Any()

  // Completion-token histogram (Feature #10). Visible decoded tokens per inference; lets operators
  // see the answer-length distribution without scraping the per-request usage block.
  private val tokenBucketBounds = longArrayOf(16, 32, 64, 128, 256, 512, 1024)
  private val tokenBucketCounts = LongArray(tokenBucketBounds.size + 1) // last == +Inf
  private var completionTokenCount = 0L
  private var completionTokenSum = 0L
  private val tokenHistLock = Any()

  // Thermal-event counter (Feature #10). The existing `relais_thermal_status` gauge only shows the
  // status at scrape time; a transient SEVERE between two scrapes is invisible. This counter,
  // incremented on every status change by the ThermalGovernor listener, captures those transients.
  // Label `level` comes from the fixed [thermalLabel] whitelist (M6: bounded cardinality).
  private val thermalEventCounts = ConcurrentHashMap<String, AtomicLong>()

  fun recordRequest(endpoint: String, status: Int) {
    requestCounts.getOrPut("$endpoint $status") { AtomicLong(0) }.incrementAndGet()
    if (status >= 500) errorsTotal.incrementAndGet()
    // Append to bounded recent-request log for the dashboard (security M6: endpoint is already
    // a normalized label — no raw path, IP, key, or FS path ever enters this buffer).
    // ageSeconds stores the unix epoch second at record time; recentRequests() converts to age.
    val nowSec = System.currentTimeMillis() / 1000L
    synchronized(requestLogLock) {
      if (requestLog.size >= REQUEST_LOG_CAPACITY) requestLog.removeFirst()
      requestLog.addLast(RequestLogEntry(endpoint = endpoint, status = status, ageSeconds = nowSec))
    }
  }

  /**
   * Returns a snapshot of the recent-request log (up to [REQUEST_LOG_CAPACITY] entries),
   * with [RequestLogEntry.ageSeconds] expressed as seconds-ago relative to now.
   * Synchronized; O(n) bounded copy — safe to call from the HTTP handler thread.
   */
  fun recentRequests(): List<RequestLogEntry> {
    val nowSec = System.currentTimeMillis() / 1000L
    return synchronized(requestLogLock) {
      requestLog.map { it.copy(ageSeconds = (nowSec - it.ageSeconds).coerceAtLeast(0L)) }
    }
  }

  /** A request that loaded stored session history (Feature #5). M6: counter only, no labels. */
  fun recordSessionHit() = sessionHitsTotal.incrementAndGet()

  /** Updates the stored-turn gauge (Feature #5). Called off the request path (record + prune). */
  fun setSessionTurns(count: Long) {
    sessionTurnsGauge = count.coerceAtLeast(0)
  }

  fun recordShed() = shedTotal.incrementAndGet()

  fun recordQueueReject() = queueRejectedTotal.incrementAndGet()

  fun incInFlight() = inFlight.incrementAndGet()

  fun decInFlight() = inFlight.decrementAndGet()

  /** Current queue depth (queued + running); used by the admission gate for Retry-After scaling. */
  fun queueDepth(): Int = inFlight.get()

  /**
   * Records inference wall-clock latency for **every** outcome — success, timeout, or error. The
   * throttled/timed-out tail is precisely what Gate 3 exists to observe, so it must land in the
   * histogram (call this from the engine's outer `finally`, not only on success).
   */
  fun recordLatency(durationSec: Double) {
    synchronized(histLock) {
      latencyCount++
      latencySum += durationSec
      var idx = bucketBoundsSec.indexOfFirst { durationSec <= it }
      if (idx < 0) idx = bucketBoundsSec.size
      bucketCounts[idx]++
    }
  }

  /** Records decoded tokens + throughput + backend — success path only (tokens unknown on failure). */
  fun recordThroughput(tokens: Int, tokensPerSec: Double, backend: String) {
    tokensTotal.addAndGet(tokens.toLong().coerceAtLeast(0))
    lastDecodeTokS = tokensPerSec
    lastBackend = backend
  }

  /**
   * Records inference latency into a **per-endpoint** histogram only (Feature #10). The unlabeled
   * global series ([recordLatency], called once from the engine's outer `finally`) stays the single
   * tail guarantee — this never writes there, so the global can't be double-counted. [endpoint] is
   * normalized through [endpointLabel] so an unknown/hostile value collapses to "other" (M6).
   */
  fun recordEndpointLatency(endpoint: String, durationSec: Double) {
    val label = endpointLabel(endpoint)
    synchronized(endpointHistLock) {
      val h = endpointHists.getOrPut(label) { EndpointHist() }
      h.count++
      h.sum += durationSec
      var idx = bucketBoundsSec.indexOfFirst { durationSec <= it }
      if (idx < 0) idx = bucketBoundsSec.size
      h.counts[idx]++
    }
  }

  /**
   * Records the visible completion-token count of one inference (Feature #10). Call on the success
   * path where the visible token count is authoritative (next to [recordThroughput]). Negative input
   * is guarded (ignored) — a histogram can't represent a negative observation.
   */
  fun recordCompletionTokens(tokens: Int) {
    if (tokens < 0) return
    synchronized(tokenHistLock) {
      completionTokenCount++
      completionTokenSum += tokens.toLong()
      var idx = tokenBucketBounds.indexOfFirst { tokens <= it }
      if (idx < 0) idx = tokenBucketBounds.size
      tokenBucketCounts[idx]++
    }
  }

  /**
   * Increments the thermal-event counter for a status change (Feature #10). Call from the
   * ThermalGovernor listener on every change so transient SEVERE between scrapes is captured.
   * [status] is mapped to a fixed [thermalLabel] (M6: bounded label cardinality).
   */
  fun recordThermalEvent(status: Int) {
    thermalEventCounts.getOrPut(thermalLabel(status)) { AtomicLong(0) }.incrementAndGet()
  }

  /**
   * Fixed whitelist that maps an arbitrary request path to a bounded endpoint label (security M6 —
   * no user-controlled metric-label cardinality). Anything outside the known endpoints becomes
   * "other". Mirrors the HTTP server's own normalizer; duplicated here so the metrics boundary is
   * self-defending even if a caller forgets to pre-normalize.
   */
  fun endpointLabel(raw: String): String =
    when {
      raw == "/generate" -> "/generate"
      raw == "/v1/chat/completions" -> "/v1/chat/completions"
      raw == "/v1/embeddings" -> "/v1/embeddings"
      raw == "/v1/models" -> "/v1/models"
      raw == "/v1/clientconfig" -> "/v1/clientconfig"
      raw == "/v1/sessions" -> "/v1/sessions"
      raw == "/v1/rag/documents" -> "/v1/rag/documents" // RAG corpus ingest/list/delete (#4)
      raw == "/v1/rag/query" -> "/v1/rag/query" // RAG retrieval (#4)
      raw == "/automation" -> "/automation" // Tasker/Automate intent ABI (#8)
      raw == "/metrics" -> "/metrics"
      raw == "/health" -> "/health"
      raw == "/" -> "/"
      else -> "other"
    }

  /**
   * Maps Android [android.os.PowerManager] THERMAL_STATUS_* integers to a fixed, lowercase metric
   * label (security M6 — bounded cardinality). Out-of-range values collapse to "unknown" without
   * throwing. Distinct from the dashboard's human label so the two surfaces can diverge freely.
   */
  fun thermalLabel(status: Int): String =
    when (status) {
      0 -> "none"
      1 -> "light"
      2 -> "moderate"
      3 -> "severe"
      4 -> "critical"
      5 -> "emergency"
      6 -> "shutdown"
      else -> "unknown"
    }

  /** Cheap RSS via /proc/self/status (avoids Debug.getPss, which can stall tens of ms). */
  private fun rssBytes(): Long =
    runCatching {
      File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
        ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024) ?: -1L
    }.getOrDefault(-1L)

  // Prometheus label values: escape backslash, double-quote, newline.
  private fun esc(v: String): String =
    v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

  /** Prometheus text exposition. Authenticated endpoint (sits behind the API-key gate). */
  fun renderProm(context: Context): String {
    val now = System.currentTimeMillis()
    val sb = StringBuilder(2048)
    fun line(s: String) = sb.append(s).append('\n')

    line("# HELP relais_process_start_time_seconds Process start (unix seconds); detects restarts.")
    line("# TYPE relais_process_start_time_seconds gauge")
    line("relais_process_start_time_seconds ${startMs / 1000.0}")

    line("# HELP relais_uptime_seconds Seconds since this process started.")
    line("# TYPE relais_uptime_seconds gauge")
    line("relais_uptime_seconds ${(now - startMs) / 1000.0}")

    line("# HELP relais_build_info Static node info (model id + last backend only — never paths).")
    line("# TYPE relais_build_info gauge")
    line("relais_build_info{model_id=\"${esc(RelaisConfig.modelId(context))}\",backend=\"${esc(lastBackend)}\"} 1")

    line("# HELP relais_engine_ready Whether the resident engine is initialized (1) or not (0).")
    line("# TYPE relais_engine_ready gauge")
    line("relais_engine_ready ${if (RelaisEngine.isReady) 1 else 0}")

    line("# HELP relais_requests_total Requests by endpoint and HTTP status.")
    line("# TYPE relais_requests_total counter")
    for ((k, v) in requestCounts) {
      val sp = k.lastIndexOf(' ')
      val endpoint = k.substring(0, sp)
      val status = k.substring(sp + 1)
      line("relais_requests_total{endpoint=\"${esc(endpoint)}\",status=\"${esc(status)}\"} ${v.get()}")
    }

    line("# HELP relais_errors_total Requests answered with a 5xx.")
    line("# TYPE relais_errors_total counter")
    line("relais_errors_total ${errorsTotal.get()}")

    line("# HELP relais_shed_total Requests shed (503) under thermal backpressure.")
    line("# TYPE relais_shed_total counter")
    line("relais_shed_total ${shedTotal.get()}")

    line("# HELP relais_queue_rejected_total Requests rejected (429) because the admission queue was full.")
    line("# TYPE relais_queue_rejected_total counter")
    line("relais_queue_rejected_total ${queueRejectedTotal.get()}")

    line("# HELP relais_tokens_generated_total Total decoded tokens served.")
    line("# TYPE relais_tokens_generated_total counter")
    line("relais_tokens_generated_total ${tokensTotal.get()}")

    line("# HELP relais_inference_duration_seconds Inference wall-clock latency.")
    line("# TYPE relais_inference_duration_seconds histogram")
    synchronized(histLock) {
      var cumulative = 0L
      for (i in bucketBoundsSec.indices) {
        cumulative += bucketCounts[i]
        line("relais_inference_duration_seconds_bucket{le=\"${bucketBoundsSec[i]}\"} $cumulative")
      }
      cumulative += bucketCounts[bucketBoundsSec.size]
      line("relais_inference_duration_seconds_bucket{le=\"+Inf\"} $cumulative")
      line("relais_inference_duration_seconds_sum $latencySum")
      line("relais_inference_duration_seconds_count $latencyCount")
    }
    // Per-endpoint series share the same metric name + bucket bounds, distinguished by the
    // `endpoint` label (Feature #10). Emitted under the same HELP/TYPE header above, but read
    // under `endpointHistLock` — the lock `recordEndpointLatency` writes under (NOT histLock), so
    // the render and the writer mutually exclude. Kept a separate, sequential block (not nested in
    // histLock) so there is no lock-ordering/deadlock risk.
    synchronized(endpointHistLock) {
      for ((label, h) in endpointHists) {
        var cum = 0L
        for (i in bucketBoundsSec.indices) {
          cum += h.counts[i]
          line("relais_inference_duration_seconds_bucket{endpoint=\"${esc(label)}\",le=\"${bucketBoundsSec[i]}\"} $cum")
        }
        cum += h.counts[bucketBoundsSec.size]
        line("relais_inference_duration_seconds_bucket{endpoint=\"${esc(label)}\",le=\"+Inf\"} $cum")
        line("relais_inference_duration_seconds_sum{endpoint=\"${esc(label)}\"} ${h.sum}")
        line("relais_inference_duration_seconds_count{endpoint=\"${esc(label)}\"} ${h.count}")
      }
    }

    line("# HELP relais_completion_tokens Visible completion tokens per inference.")
    line("# TYPE relais_completion_tokens histogram")
    synchronized(tokenHistLock) {
      var cumulative = 0L
      for (i in tokenBucketBounds.indices) {
        cumulative += tokenBucketCounts[i]
        line("relais_completion_tokens_bucket{le=\"${tokenBucketBounds[i]}\"} $cumulative")
      }
      cumulative += tokenBucketCounts[tokenBucketBounds.size]
      line("relais_completion_tokens_bucket{le=\"+Inf\"} $cumulative")
      line("relais_completion_tokens_sum $completionTokenSum")
      line("relais_completion_tokens_count $completionTokenCount")
    }

    line("# HELP relais_thermal_events_total Thermal status-change events by level (catches transients the gauge misses).")
    line("# TYPE relais_thermal_events_total counter")
    for ((level, v) in thermalEventCounts) {
      line("relais_thermal_events_total{level=\"${esc(level)}\"} ${v.get()}")
    }

    line("# HELP relais_decode_tokens_per_second Decode throughput of the most recent inference.")
    line("# TYPE relais_decode_tokens_per_second gauge")
    line("relais_decode_tokens_per_second $lastDecodeTokS")

    line("# HELP relais_thermal_status Android THERMAL_STATUS_* (0=none .. 6=shutdown).")
    line("# TYPE relais_thermal_status gauge")
    line("relais_thermal_status ${ThermalGovernor.statusValue}")

    line("# HELP relais_thermal_headroom Forecast headroom (>=1.0 == throttling); -1 if unavailable.")
    line("# TYPE relais_thermal_headroom gauge")
    line("relais_thermal_headroom ${ThermalGovernor.headroomOrSentinel()}")

    line("# HELP relais_memory_rss_bytes Resident set size (VmRSS); -1 if unreadable.")
    line("# TYPE relais_memory_rss_bytes gauge")
    line("relais_memory_rss_bytes ${rssBytes()}")

    line("# HELP relais_queue_depth In-flight + queued inference requests.")
    line("# TYPE relais_queue_depth gauge")
    line("relais_queue_depth ${inFlight.get()}")

    line("# HELP relais_rate_limiter_tracked_ips Distinct client IPs currently tracked (DoS canary).")
    line("# TYPE relais_rate_limiter_tracked_ips gauge")
    line("relais_rate_limiter_tracked_ips ${RateLimiterStats.trackedIps}")

    line("# HELP relais_restarts_total Process (re)starts observed by the watchdog/service.")
    line("# TYPE relais_restarts_total counter")
    line("relais_restarts_total ${RelaisConfig.restartCount(context)}")

    line("# HELP relais_session_turns Stored session-memory turns at last observation (0 when disabled).")
    line("# TYPE relais_session_turns gauge")
    line("relais_session_turns $sessionTurnsGauge")

    line("# HELP relais_session_hits_total Requests that loaded stored session history.")
    line("# TYPE relais_session_hits_total counter")
    line("relais_session_hits_total ${sessionHitsTotal.get()}")

    return sb.toString()
  }

  /** JSON view for the in-app HUD; includes precomputed p50/p95 (not exposed via Prometheus). */
  fun renderJson(context: Context): JSONObject {
    val now = System.currentTimeMillis()
    val (p50, p95) =
      synchronized(histLock) { quantile(0.50) to quantile(0.95) }
    return JSONObject()
      .put("uptime_seconds", (now - startMs) / 1000.0)
      .put("model_id", RelaisConfig.modelId(context))
      .put("backend", lastBackend)
      .put("engine_ready", RelaisEngine.isReady)
      .put("tokens_generated_total", tokensTotal.get())
      .put("errors_total", errorsTotal.get())
      .put("shed_total", shedTotal.get())
      .put("queue_rejected_total", queueRejectedTotal.get())
      .put("decode_tokens_per_second", lastDecodeTokS)
      .put("inference_p50_seconds", p50)
      .put("inference_p95_seconds", p95)
      .put("thermal_status", ThermalGovernor.statusValue)
      .put("thermal_headroom", ThermalGovernor.headroomOrSentinel())
      .put("memory_rss_bytes", rssBytes())
      .put("queue_depth", inFlight.get())
      .put("restarts_total", RelaisConfig.restartCount(context))
  }

  /**
   * Test seam: clears the Feature #10 increment state (per-endpoint latency, completion-token
   * histogram, thermal events) AND the global latency histogram, so each test starts from zero in
   * this process-global object. Does not touch the request-count map or counters used elsewhere.
   */
  fun resetIncrementsForTest() {
    synchronized(histLock) {
      bucketCounts.fill(0L)
      latencyCount = 0L
      latencySum = 0.0
    }
    synchronized(endpointHistLock) { endpointHists.clear() }
    synchronized(tokenHistLock) {
      tokenBucketCounts.fill(0L)
      completionTokenCount = 0L
      completionTokenSum = 0L
    }
    thermalEventCounts.clear()
  }

  /** Coarse quantile from the cumulative histogram (HUD only). Caller holds [histLock]. */
  private fun quantile(q: Double): Double {
    if (latencyCount == 0L) return 0.0
    val target = q * latencyCount
    var cumulative = 0L
    for (i in bucketBoundsSec.indices) {
      cumulative += bucketCounts[i]
      if (cumulative >= target) return bucketBoundsSec[i]
    }
    return bucketBoundsSec.last()
  }
}

/** Lets [RelaisMetrics] read the rate-limiter's tracked-IP count without a back-reference. */
object RateLimiterStats {
  @Volatile var trackedIps: Int = 0
}
