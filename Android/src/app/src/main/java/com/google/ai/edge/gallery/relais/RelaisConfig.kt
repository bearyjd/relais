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
  private const val KEY_MODEL_ID = "model_id"
  private const val KEY_HF_TOKEN = "hf_token"
  private const val KEY_MODEL_PATH = "model_path"
  private const val KEY_TLS_PASS = "tls_keystore_pass"

  /**
   * Default model the node self-provisions. The litert-community Gemma-4-E4B-it repo is an **open**
   * HuggingFace repo — it downloaded with no auth in the spike, so first run needs no token. Switch
   * to a license-gated `google/gemma-*` id only alongside a token set via [setHfToken].
   */
  const val DEFAULT_MODEL_ID = "litert-community/gemma-4-E4B-it-litert-lm"

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

  /** Allowlist model id the node downloads & serves; defaults to the open [DEFAULT_MODEL_ID]. */
  fun modelId(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL_ID, null)
      ?: DEFAULT_MODEL_ID

  fun setModelId(context: Context, value: String) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val changed = prefs.getString(KEY_MODEL_ID, null) != value
    prefs.edit().putString(KEY_MODEL_ID, value).apply()
    // Switching models must invalidate the cached provisioned path, otherwise the offline
    // fast-path in RelaisModelProvisioner.ensureModel would keep serving the previous model's
    // file (it still exists on disk) and never resolve the new id.
    if (changed) prefs.edit().remove(KEY_MODEL_PATH).apply()
  }

  /**
   * Optional HuggingFace access token for license-gated repos (e.g. official `google/gemma-*`).
   * Null for open models. The headless node cannot do interactive OAuth, so a gated model requires
   * this to be pre-set via [setHfToken].
   */
  fun hfToken(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HF_TOKEN, null)

  fun setHfToken(context: Context, value: String?) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HF_TOKEN, value).apply()
  }

  /**
   * Last successfully provisioned on-disk model path. Persisted so a restarted node whose model is
   * already downloaded can boot **offline** without re-fetching the allowlist. Null until first
   * provision; see [RelaisModelProvisioner.ensureModel].
   */
  fun modelPath(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL_PATH, null)

  fun setModelPath(context: Context, value: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODEL_PATH, value).apply()
  }

  /**
   * Random per-install password protecting the runtime-generated TLS keystore (app-private file).
   * Generated and persisted on first access, mirroring [apiKey]. Not a shared/committed secret.
   */
  fun tlsKeystorePassword(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.getString(KEY_TLS_PASS, null)?.let {
      return it
    }
    val pass = UUID.randomUUID().toString().replace("-", "")
    prefs.edit().putString(KEY_TLS_PASS, pass).apply()
    return pass
  }
}
