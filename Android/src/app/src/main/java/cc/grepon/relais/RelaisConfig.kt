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

package cc.grepon.relais

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** Node config: API key for the LAN endpoint and the opt-in boot auto-start flag. */
object RelaisConfig {
  private const val TAG = "RelaisConfig"
  private const val PREFS = "relais"
  private const val SECURE_PREFS = "relais_secure"
  private const val KEY_API = "api_key"
  private const val KEY_AUTOSTART = "auto_start"
  private const val KEY_SHOULD_RUN = "should_run"
  private const val KEY_MODEL_ID = "model_id"
  private const val KEY_HF_TOKEN = "hf_token"
  private const val KEY_MODEL_PATH = "model_path"
  private const val KEY_TLS_PASS = "tls_keystore_pass"
  private const val KEY_RESTARTS = "restarts_total"

  @Volatile private var securePrefsCache: SharedPreferences? = null

  /**
   * Default model the node self-provisions. The litert-community Gemma-4-E4B-it repo is an **open**
   * HuggingFace repo — it downloaded with no auth in the spike, so first run needs no token. Switch
   * to a license-gated `google/gemma-*` id only alongside a token set via [setHfToken].
   */
  const val DEFAULT_MODEL_ID = "litert-community/gemma-4-E4B-it-litert-lm"

  /** Plaintext prefs for non-sensitive node config (flags, model id, restart counter). */
  private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /**
   * Encrypted prefs for secrets — API key, TLS keystore password, HF token. Backed by
   * [EncryptedSharedPreferences] (AndroidKeystore-wrapped AES). A one-time [migrateSecrets] moves
   * any pre-existing plaintext secrets out of [PREFS]. Security H2: no plaintext secret at rest, so
   * an `adb backup` / root / prefs-disclosure bug no longer yields the live key. The operator can
   * still read the key in-app because the node decrypts it for display.
   */
  private fun securePrefs(context: Context): SharedPreferences {
    securePrefsCache?.let { return it }
    return synchronized(this) {
      securePrefsCache
        ?: run {
          val appCtx = context.applicationContext
          // HIGH-3: EncryptedSharedPreferences.create can throw if the Keystore key is invalidated
          // (OS restore, lock-credential change on some OEMs). On a headless device with no
          // interactive recovery, a throw would brick the node into a watchdog restart loop. Reset
          // the unreadable store once and recreate; secrets regenerate downstream.
          val sp =
            runCatching { createEncrypted(appCtx) }
              .getOrElse {
                Log.e(TAG, "secure prefs unreadable; resetting encrypted store", it)
                appCtx.deleteSharedPreferences(SECURE_PREFS)
                createEncrypted(appCtx)
              }
          migrateSecrets(appCtx, sp)
          securePrefsCache = sp
          sp
        }
    }
  }

  private fun createEncrypted(appCtx: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(appCtx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
      appCtx,
      SECURE_PREFS,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  /** Move any legacy plaintext secrets from [PREFS] into the encrypted store, once. */
  private fun migrateSecrets(context: Context, secure: SharedPreferences) {
    val plain = prefs(context)
    for (key in listOf(KEY_API, KEY_TLS_PASS, KEY_HF_TOKEN)) {
      val legacy = plain.getString(key, null) ?: continue
      if (secure.getString(key, null) == null) secure.edit().putString(key, legacy).apply()
      plain.edit().remove(key).apply()
    }
  }

  /** Intent-to-run latch: set true while the node is meant to be up; the watchdog honors it. */
  fun shouldRun(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOULD_RUN, false)

  fun setShouldRun(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean(KEY_SHOULD_RUN, value).apply()
  }

  /** Stable per-install API key; generated and persisted (encrypted) on first access. */
  fun apiKey(context: Context): String {
    val sp = securePrefs(context)
    sp.getString(KEY_API, null)?.let {
      return it
    }
    val key = UUID.randomUUID().toString().replace("-", "")
    sp.edit().putString(KEY_API, key).apply()
    return key
  }

  /** Off by default — the node does not start on boot unless explicitly enabled. */
  fun autoStartEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOSTART, false)

  fun setAutoStart(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
  }

  /** Allowlist model id the node downloads & serves; defaults to the open [DEFAULT_MODEL_ID]. */
  fun modelId(context: Context): String =
    prefs(context).getString(KEY_MODEL_ID, null) ?: DEFAULT_MODEL_ID

  fun setModelId(context: Context, value: String) {
    val p = prefs(context)
    val changed = p.getString(KEY_MODEL_ID, null) != value
    p.edit().putString(KEY_MODEL_ID, value).apply()
    // Switching models must invalidate the cached provisioned path, otherwise the offline
    // fast-path in RelaisModelProvisioner.ensureModel would keep serving the previous model's
    // file (it still exists on disk) and never resolve the new id.
    if (changed) p.edit().remove(KEY_MODEL_PATH).apply()
  }

  /**
   * Optional HuggingFace access token for license-gated repos (e.g. official `google/gemma-*`).
   * Null for open models. Stored encrypted. The headless node cannot do interactive OAuth, so a
   * gated model requires this to be pre-set via [setHfToken].
   */
  fun hfToken(context: Context): String? = securePrefs(context).getString(KEY_HF_TOKEN, null)

  fun setHfToken(context: Context, value: String?) {
    securePrefs(context).edit().putString(KEY_HF_TOKEN, value).apply()
  }

  /**
   * Last successfully provisioned on-disk model path. Persisted so a restarted node whose model is
   * already downloaded can boot **offline** without re-fetching the allowlist. Null until first
   * provision; see [RelaisModelProvisioner.ensureModel].
   */
  fun modelPath(context: Context): String? = prefs(context).getString(KEY_MODEL_PATH, null)

  fun setModelPath(context: Context, value: String) {
    prefs(context).edit().putString(KEY_MODEL_PATH, value).apply()
  }

  /**
   * Random per-install password protecting the runtime-generated TLS keystore. Generated and
   * persisted (encrypted) on first access, mirroring [apiKey]. Not a shared/committed secret.
   */
  fun tlsKeystorePassword(context: Context): String {
    val sp = securePrefs(context)
    sp.getString(KEY_TLS_PASS, null)?.let {
      return it
    }
    val pass = UUID.randomUUID().toString().replace("-", "")
    sp.edit().putString(KEY_TLS_PASS, pass).apply()
    return pass
  }

  /** Process (re)starts observed — survives process death; surfaced via /metrics (Gate 3). */
  fun restartCount(context: Context): Long = prefs(context).getLong(KEY_RESTARTS, 0L)

  fun incrementRestartCount(context: Context) {
    val p = prefs(context)
    p.edit().putLong(KEY_RESTARTS, p.getLong(KEY_RESTARTS, 0L) + 1).apply()
  }
}
