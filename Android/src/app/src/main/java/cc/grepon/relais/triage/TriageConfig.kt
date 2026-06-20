/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais.triage

import android.content.Context
import android.content.SharedPreferences

/**
 * Opt-in flags + per-app allowlist for notification triage. Stored in the same plaintext `relais`
 * SharedPreferences as the rest of [cc.grepon.relais.RelaisConfig] (package names only — never
 * notification content). Every flag defaults to the privacy-preserving value: triage off, allowlist
 * empty (default-deny), consent not yet given.
 */
object TriageConfig {
  private const val PREFS = "relais"

  private const val KEY_ENABLED = "triage_enabled"
  private const val KEY_URGENT = "triage_urgent_enabled"
  private const val KEY_CONSENTED = "triage_consented"
  private const val KEY_ALLOWLIST = "triage_allowlist"
  private const val KEY_INTERVAL = "triage_interval_min"

  const val MIN_INTERVAL_MIN = 15
  const val MAX_INTERVAL_MIN = 1440
  const val DEFAULT_INTERVAL_MIN = 60

  private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

  fun setEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
  }

  /** Whether the near-real-time urgent-surfacing path is active (vs. digest-only). */
  fun urgentEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_URGENT, true)

  fun setUrgentEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_URGENT, enabled).apply()
  }

  /** One-time consent: the user has acknowledged the privacy disclosure for reading notifications. */
  fun consented(context: Context): Boolean = prefs(context).getBoolean(KEY_CONSENTED, false)

  fun setConsented(context: Context, consented: Boolean) {
    prefs(context).edit().putBoolean(KEY_CONSENTED, consented).apply()
  }

  /** Default-deny set of allowlisted package names. Empty means nothing is triaged. */
  fun allowlist(context: Context): Set<String> =
    prefs(context).getString(KEY_ALLOWLIST, null)
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.toSet()
      ?: emptySet()

  fun setAllowlist(context: Context, packages: Set<String>) {
    val cleaned = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
    prefs(context).edit().putString(KEY_ALLOWLIST, cleaned.joinToString(",")).apply()
  }

  /** Digest interval in minutes, clamped to the WorkManager-safe range. */
  fun intervalMinutes(context: Context): Long =
    prefs(context)
      .getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MIN)
      .coerceIn(MIN_INTERVAL_MIN, MAX_INTERVAL_MIN)
      .toLong()

  fun setIntervalMinutes(context: Context, minutes: Int) {
    val clamped = minutes.coerceIn(MIN_INTERVAL_MIN, MAX_INTERVAL_MIN)
    prefs(context).edit().putInt(KEY_INTERVAL, clamped).apply()
  }
}
