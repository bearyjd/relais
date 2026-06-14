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
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors

private const val TAG = "ThermalGovernor"
private const val FORECAST_SEC = 10
private const val HEADROOM_CACHE_MS = 2_000L // getThermalHeadroom is rate-limited; cache it
private const val HEADROOM_SHED = 0.95f
private const val DECODE_FLOOR_TOK_S = 3.0 // sustained decode below this == throttle pressure
private const val MIN_THROUGHPUT_SAMPLES = 3
private const val MODERATE_COOLDOWN_MS = 1_500L

/**
 * Thermal-aware backpressure for the node (Gate 3). The GOAL's named #1 risk is throttle-under-load;
 * this is its mitigation. Three independent signals, because no single one is reliable on Tensor:
 *  1. [PowerManager] thermal **status** (NONE..SHUTDOWN) — the OS verdict.
 *  2. [PowerManager.getThermalHeadroom] **forecast** — predictive, but rate-limited and may be NaN.
 *  3. measured **decode throughput** — the real cliff: the kernel can throttle the GPU clock before
 *     the OS reports SEVERE, which shows up as tok/s collapse. This is the signal the GOAL cares
 *     about ("graceful latency growth, not death").
 *
 * Policy:
 *  - LIGHT/none  -> serve normally.
 *  - MODERATE    -> insert a cool-down gap between requests ([cooldownMs]).
 *  - SEVERE / headroom>=0.95 / throughput-floor breached -> shed: new inference => 503 + Retry-After.
 *  - CRITICAL/SHUTDOWN -> [shouldTruncate]: cooperatively cut the in-flight decode to protect device.
 */
object ThermalGovernor {
  @Volatile var statusValue: Int = PowerManager.THERMAL_STATUS_NONE
    private set

  private var powerManager: PowerManager? = null
  private var listener: PowerManager.OnThermalStatusChangedListener? = null
  private val executor = Executors.newSingleThreadExecutor { Thread(it, "relais-thermal") }

  @Volatile private var headroomCache = -1f
  @Volatile private var headroomCachedAt = 0L

  @Volatile private var decodeEwma = 0.0
  @Volatile private var decodeSamples = 0

  // Configurable shed thresholds (Feature #10). Default to the historical consts; [register] pushes
  // the operator-set, already-CLAMPED values from RelaisConfig in. Holding them as injectable fields
  // (rather than reading RelaisConfig from shouldShed) keeps the hot path Context-free and the
  // device-safety invariant headlessly testable. The values are pre-clamped, so the throughput floor
  // is always > 0 and the SEVERE backstop in [shouldShed] is independent of all three.
  @Volatile private var shedHeadroom = HEADROOM_SHED
  @Volatile private var decodeFloorTokS = DECODE_FLOOR_TOK_S
  @Volatile private var moderateCooldownMs = MODERATE_COOLDOWN_MS

  fun register(context: Context) {
    if (listener != null) return
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    powerManager = pm
    // Load operator-configured, already-clamped shed thresholds (defaults == the consts).
    shedHeadroom = RelaisConfig.shedHeadroom(context)
    decodeFloorTokS = RelaisConfig.decodeFloorTokS(context)
    moderateCooldownMs = RelaisConfig.moderateCooldownMs(context)
    statusValue = runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
    val l = PowerManager.OnThermalStatusChangedListener { status ->
      statusValue = status
      // Record the transition as a counter event so a transient SEVERE between scrapes isn't lost
      // (the `relais_thermal_status` gauge only shows the value at scrape time). Feature #10.
      RelaisMetrics.recordThermalEvent(status)
      Log.i(TAG, "thermal status -> $status")
    }
    runCatching { pm.addThermalStatusListener(executor, l) }
      .onFailure { Log.e(TAG, "addThermalStatusListener failed", it) }
    listener = l
  }

  fun unregister() {
    val pm = powerManager
    val l = listener
    if (pm != null && l != null) runCatching { pm.removeThermalStatusListener(l) }
    listener = null
    powerManager = null
  }

  /** Forecast headroom (>=1.0 == at throttle threshold). Returns -1 sentinel if unavailable/NaN. */
  fun headroomOrSentinel(): Float {
    val pm = powerManager ?: return -1f
    val now = System.currentTimeMillis()
    if (now - headroomCachedAt < HEADROOM_CACHE_MS) return headroomCache
    val hr = runCatching { pm.getThermalHeadroom(FORECAST_SEC) }.getOrDefault(Float.NaN)
    headroomCache = if (hr.isNaN()) -1f else hr
    headroomCachedAt = now
    return headroomCache
  }

  private fun throughputFloorBreached(): Boolean =
    decodeSamples >= MIN_THROUGHPUT_SAMPLES && decodeEwma in 0.001..decodeFloorTokS

  /** True when new inference should be rejected with 503 + Retry-After. */
  fun shouldShed(): Boolean {
    // DEVICE-SAFETY INVARIANT: the SEVERE backstop is hardcoded and FIRST — independent of any
    // (clamped or not) config value. No threshold setting can make this return false while SEVERE.
    if (statusValue >= PowerManager.THERMAL_STATUS_SEVERE) return true
    if (headroomOrSentinel() >= shedHeadroom) return true
    if (throughputFloorBreached()) return true
    return false
  }

  /** Cooperatively cut an in-flight decode (device-protective) once thermal is critical. */
  fun shouldTruncate(): Boolean = statusValue >= PowerManager.THERMAL_STATUS_CRITICAL

  /** Cool-down gap to insert before serving while MODERATE; 0 otherwise. Caller applies it. */
  fun cooldownMs(): Long = if (statusValue == PowerManager.THERMAL_STATUS_MODERATE) moderateCooldownMs else 0L

  /** Base Retry-After seconds for a shed response (HTTP layer adds jitter). */
  fun retryAfterSeconds(): Int =
    when {
      statusValue >= PowerManager.THERMAL_STATUS_CRITICAL -> 30
      statusValue >= PowerManager.THERMAL_STATUS_SEVERE -> 10
      else -> 8
    }

  /** Feed measured decode throughput (tok/s) after each inference; powers the floor signal. */
  fun onDecodeThroughput(tokS: Double) {
    if (tokS <= 0.0) return
    decodeEwma = if (decodeSamples == 0) tokS else 0.5 * decodeEwma + 0.5 * tokS
    decodeSamples++
  }

  /** Test/reset seam. */
  fun resetThroughputForTest() {
    decodeEwma = 0.0
    decodeSamples = 0
  }

  /** Test seam: drive the thermal status directly (no real hardware in CI). */
  fun setStatusForTest(value: Int) {
    statusValue = value
  }

  /**
   * Test seam: push shed thresholds directly (mirrors what [register] does from RelaisConfig) so the
   * device-safety invariant can be verified headlessly without a Context. Values are clamped here too
   * — the floor stays > 0 — so a test can't accidentally model a configuration the production read
   * path would never produce.
   */
  fun setThresholdsForTest(shedHeadroom: Float, decodeFloorTokS: Double, moderateCooldownMs: Long) {
    this.shedHeadroom = shedHeadroom.coerceIn(0.5f, 1.5f)
    this.decodeFloorTokS = decodeFloorTokS.coerceIn(0.5, 50.0)
    this.moderateCooldownMs = moderateCooldownMs.coerceIn(0L, 10_000L)
  }

  /** Test seam: restore the default (historical-const) thresholds. */
  fun resetThresholdsForTest() {
    shedHeadroom = HEADROOM_SHED
    decodeFloorTokS = DECODE_FLOOR_TOK_S
    moderateCooldownMs = MODERATE_COOLDOWN_MS
  }
}
