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

package cc.grepon.relais.tts

import android.content.Context
import android.util.Log
import cc.grepon.relais.RelaisConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The concrete on-device TTS engine (issue #168): **sherpa-onnx** running a **Piper (VITS)** voice.
 * Loads the provisioned voice lazily under a lock and holds ONE resident [OfflineTts] (reloaded only if
 * the operator switches voices). GMS-free, so it registers in `main` and serves on all flavors.
 *
 * Measured on rango / Tensor G5: Piper `en_US-lessac-medium` at **RTF 0.12** (`TtsProbe`).
 */
object SherpaTtsEngine : RelaisTtsEngine {
  private const val TAG = "RelaisTts"
  private const val NUM_THREADS = 2

  private val lock = Any() // guards engine load/swap
  private val synthLock = Any() // serializes native generate() on the single resident OfflineTts
  @Volatile private var tts: OfflineTts? = null
  @Volatile private var loadedVoiceId: String? = null
  private val provisioning = AtomicBoolean(false)

  /** The operator-selected voice (defaults to [DEFAULT_TTS_VOICE_ID]); falls back if the id is unknown. */
  private fun selectedVoice(context: Context): TtsVoice =
    ttsVoiceById(RelaisConfig.ttsVoiceId(context)) ?: TTS_VOICE_PIPER_LESSAC

  override fun isAvailable(context: Context): Boolean {
    val voice = selectedVoice(context)
    return TtsVoiceProvisioner.isProvisioned(context, voice) && ensureLoaded(context, voice) != null
  }

  override fun canProvision(context: Context): Boolean =
    !TtsVoiceProvisioner.isProvisioned(context, selectedVoice(context))

  override fun availability(context: Context): TtsAvailability {
    // Single consistent snapshot (avoids the isAvailable/canProvision TOCTOU). A voice always exists,
    // so once registered the engine is only ever READY (provisioned + loads) or PROVISIONING.
    val voice = selectedVoice(context)
    return if (TtsVoiceProvisioner.isProvisioned(context, voice) && ensureLoaded(context, voice) != null) {
      TtsAvailability.READY
    } else {
      TtsAvailability.PROVISIONING
    }
  }

  override fun ensureProvisioningStarted(context: Context) {
    val voice = selectedVoice(context)
    if (TtsVoiceProvisioner.isProvisioned(context, voice)) return
    // One-shot, non-blocking: the request thread never waits on the ~60-90 MB fetch.
    if (provisioning.compareAndSet(false, true)) {
      val app = context.applicationContext
      Thread({
        try {
          TtsVoiceProvisioner.ensure(app, voice)
          ensureLoaded(app, voice) // warm the engine so the next request is READY
        } catch (t: Throwable) {
          Log.e(TAG, "TTS voice provisioning failed: ${t.message}", t)
        } finally {
          provisioning.set(false)
        }
      }, "relais-tts-provision").start()
    }
  }

  override fun synthesize(context: Context, text: String, voice: String?, speed: Float): TtsAudio {
    // `voice` selects a speaker within the model in future multi-voice builds; the single Piper voice
    // ignores it (sid 0). Engine-switch by voice id is an operator setting, not a per-request param.
    val v = selectedVoice(context)
    val engine =
      ensureLoaded(context, v)
        ?: error("TTS voice not provisioned: ${v.id}")
    // Serialize native synthesis: one resident OfflineTts, so concurrent /v1/audio/speech requests
    // don't call generate() on it in parallel.
    val audio = synchronized(synthLock) { engine.generate(text = text, sid = 0, speed = speed) }
    return TtsAudio(samples = audio.samples, sampleRate = audio.sampleRate)
  }

  /** Loads (or returns) the resident engine for [voice]; null if the voice isn't on disk. Reloads on switch. */
  private fun ensureLoaded(context: Context, voice: TtsVoice): OfflineTts? {
    tts?.let { if (loadedVoiceId == voice.id) return it }
    if (!TtsVoiceProvisioner.isProvisioned(context, voice)) return null
    synchronized(lock) {
      tts?.let { if (loadedVoiceId == voice.id) return it }
      val dir = TtsVoiceProvisioner.voiceDir(context, voice)
      val onnx = File(dir, voice.onnxName)
      val tokens = File(dir, "tokens.txt")
      val dataDir = File(dir, "espeak-ng-data")
      if (!onnx.isFile || !tokens.isFile || !dataDir.isDirectory) return null
      // Release any previously-loaded (different) voice before swapping.
      tts?.let { runCatching { it.release() } }
      val config =
        OfflineTtsConfig(
          model =
            OfflineTtsModelConfig(
              vits =
                OfflineTtsVitsModelConfig(
                  model = onnx.absolutePath,
                  tokens = tokens.absolutePath,
                  dataDir = dataDir.absolutePath,
                ),
              numThreads = NUM_THREADS,
              provider = "cpu",
              debug = false,
            )
        )
      val loaded = OfflineTts(config = config)
      tts = loaded
      loadedVoiceId = voice.id
      Log.i(TAG, "TTS engine loaded: ${voice.id} (sampleRate=${loaded.sampleRate()})")
      return loaded
    }
  }
}
