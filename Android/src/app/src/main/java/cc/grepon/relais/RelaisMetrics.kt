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
