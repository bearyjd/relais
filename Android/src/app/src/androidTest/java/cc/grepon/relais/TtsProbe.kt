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

package cc.grepon.relais

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.tts.SherpaTtsEngine
import cc.grepon.relais.tts.TTS_VOICE_PIPER_LESSAC
import cc.grepon.relais.tts.TtsAvailability
import cc.grepon.relais.tts.TtsVoiceProvisioner
import cc.grepon.relais.tts.TtsWav
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device TTS runtime spike for issue #168 — proves the chosen stack (**sherpa-onnx** runtime +
 * **Piper** voice) actually synthesizes speech on rango, and MEASURES the real-time factor (RTF) on
 * the Tensor G5 CPU. This is the "measured RTF on rango" acceptance criterion of #168; the published
 * proxies were Piper ≈0.2 (Pi4) / Kokoro ≈0.45 (Helio G99).
 *
 * RTF = synthesis wall-time / produced-audio-duration. RTF < 1 is faster than real time; lower is
 * better. First call includes lazy native init, so a warm-up run precedes the measured run.
 *
 * Runs against a Piper voice staged on-device (dir with `<voice>.onnx`, `tokens.txt`,
 * `espeak-ng-data/`), pushed via adb. Uses the filesystem path (assetManager = null), the same way
 * the production RelaisTtsEngine will load a provisioned voice.
 *
 * Run (rango; model staged under the app files dir):
 *   adb shell am instrument -w -e class cc.grepon.relais.TtsProbe \
 *     -e dir /storage/emulated/0/Android/data/<pkg>/files/tts/vits-piper-en_US-lessac-medium \
 *     -e onnx en_US-lessac-medium.onnx \
 *     <pkg>.test/androidx.test.runner.AndroidJUnitRunner
 * Watch: adb logcat -s RelaisTtsProbe
 */
@RunWith(AndroidJUnit4::class)
class TtsProbe {

  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun piperSynthesizesAndMeasuresRtf() {
    val dir = args.getString("dir")
    assumeTrue("pass -e dir <staged voice dir>", dir != null)
    val onnxName = args.getString("onnx") ?: "model.onnx"
    val onnx = File(dir, onnxName)
    val tokens = File(dir, "tokens.txt")
    val dataDir = File(dir, "espeak-ng-data")
    assumeTrue("voice .onnx missing: ${onnx.absolutePath}", onnx.canRead())
    assumeTrue("tokens.txt missing", tokens.canRead())
    assumeTrue("espeak-ng-data/ missing", dataDir.isDirectory)

    val vits =
      OfflineTtsVitsModelConfig(
        model = onnx.absolutePath,
        tokens = tokens.absolutePath,
        dataDir = dataDir.absolutePath,
      )
    val modelConfig =
      OfflineTtsModelConfig(vits = vits, numThreads = 2, provider = "cpu", debug = false)
    val config = OfflineTtsConfig(model = modelConfig)

    val initStart = System.nanoTime()
    val tts = OfflineTts(config = config)
    val initMs = (System.nanoTime() - initStart) / 1_000_000
    Log.i(TAG, "engine ready in ${initMs} ms; sampleRate=${tts.sampleRate()} speakers=${tts.numSpeakers()}")

    try {
      // Warm-up (absorbs lazy native/graph init so the measured run reflects steady-state decode).
      tts.generate(text = "Warm up.", sid = 0, speed = 1.0f)

      val text =
        "Relais is a headless on-device language model node. It runs a model on the phone and serves " +
          "an OpenAI-compatible interface over the local network, so any client can reach it without a cloud."
      val t0 = System.nanoTime()
      val audio = tts.generate(text = text, sid = 0, speed = 1.0f)
      val synthMs = (System.nanoTime() - t0) / 1_000_000

      val samples = audio.samples.size
      val sr = audio.sampleRate
      val audioMs = if (sr > 0) samples * 1000L / sr else 0L
      val rtf = if (audioMs > 0) synthMs.toDouble() / audioMs.toDouble() else -1.0

      // Save the wav so the output can be pulled + listened to (proves it's real speech, not silence).
      val outWav = File(dir, "probe_out.wav")
      val saved = runCatching { audio.save(outWav.absolutePath) }.getOrDefault(false)

      Log.i(
        TAG,
        "RESULT synthMs=$synthMs audioMs=$audioMs samples=$samples sampleRate=$sr " +
          "RTF=${"%.3f".format(rtf)} initMs=$initMs charCount=${text.length} wavSaved=$saved (${outWav.absolutePath})",
      )
      Log.i(
        TAG,
        "VERDICT: ${if (rtf in 0.0..1.0) "PASS — faster than real time (RTF ${"%.2f".format(rtf)})" else "SLOW/FAIL RTF=${"%.2f".format(rtf)}"}",
      )

      assertTrue("no audio produced", samples > 0)
      assertTrue("RTF not real-time on G5 CPU: $rtf", rtf in 0.0..1.0)
    } finally {
      tts.release()
    }
  }

  /**
   * Production-path leg: drives [SherpaTtsEngine.synthesize] (the same call `/v1/audio/speech` makes)
   * against the provisioned default Piper voice, then WAV-encodes via [TtsWav]. Validates the whole
   * production stack minus the HTTP socket — provisioning-path resolution, the resident engine, and
   * the WAV container — not just the raw sherpa API. Requires the default voice staged under the app
   * files dir (`files/tts/vits-piper-en_US-lessac-medium`).
   */
  @Test
  fun productionEngineSynthesizesWav() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assumeTrue(
      "default voice not provisioned under files/tts — push it first",
      TtsVoiceProvisioner.isProvisioned(context, TTS_VOICE_PIPER_LESSAC),
    )
    assertEquals(
      "engine should report READY once the voice is provisioned",
      TtsAvailability.READY,
      SherpaTtsEngine.availability(context),
    )

    val text = "The relay station is online and speaking."
    val audio = SherpaTtsEngine.synthesize(context, text, voice = null, speed = 1.0f)
    val wav = TtsWav.wav(audio.samples, audio.sampleRate)

    Log.i(
      TAG,
      "PROD-PATH samples=${audio.samples.size} sampleRate=${audio.sampleRate} durationMs=${audio.durationMs} " +
        "wavBytes=${wav.size} riff=${String(wav.copyOfRange(0, 4))}",
    )
    assertTrue("no audio from production engine", audio.samples.isNotEmpty())
    assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
    assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
    assertEquals("wav length mismatch", 44 + audio.samples.size * 2, wav.size)
  }

  private companion object {
    const val TAG = "RelaisTtsProbe"
  }
}
