/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.relais

import android.content.Context
import java.util.UUID

/** Node config: API key for the LAN endpoint and the opt-in boot auto-start flag. */
object RelaisConfig {
  private const val PREFS = "relais"
  private const val KEY_API = "api_key"
  private const val KEY_AUTOSTART = "auto_start"
  private const val KEY_SHOULD_RUN = "should_run"

  /** Intent-to-run latch: set true while the node is meant to be up; the watchdog honors it. */
  fun shouldRun(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOULD_RUN, false)

  fun setShouldRun(context: Context, value: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOULD_RUN, value).apply()
  }

  /** Stable per-install API key; generated and persisted on first access. */
  fun apiKey(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.getString(KEY_API, null)?.let {
      return it
    }
    val key = UUID.randomUUID().toString().replace("-", "")
    prefs.edit().putString(KEY_API, key).apply()
    return key
  }

  /** Off by default — the node does not start on boot unless explicitly enabled. */
  fun autoStartEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART, false)

  fun setAutoStart(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
  }
}
