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

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.tts.SherpaTtsEngine
import cc.grepon.relais.tts.TtsAudio
import cc.grepon.relais.tts.TtsAvailability
import cc.grepon.relais.tts.TtsPlayer
import cc.grepon.relais.tts.speakableText
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device playback probe for in-app speech (issue #211). Covers exactly what the 53 JVM tests
 * cannot: a real [android.media.AudioTrack], real audio-focus arbitration, and real timing for the
 * supersede/stop paths.
 *
 * Three code-review passes flagged these as unverifiable without hardware — this is that check.
 *
 * Most tests drive a synthesized **tone** rather than speech: [TtsPlayer] neither knows nor cares
 * that the samples are words, and a tone exercises the identical AudioTrack/focus/drain path while
 * letting the probe run on a device with no voice provisioned. The final test uses the real Piper
 * voice and is skipped (not failed) when the voice isn't on disk.
 *
 * Run (one line, per this repo's probe convention):
 * ```
 * adb -s <serial> shell am instrument -w \
 *   -e class cc.grepon.relais.SpeechPlaybackProbe \
 *   com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * Log tag: `RelaisSpeechProbe`.
 */
@RunWith(AndroidJUnit4::class)
class SpeechPlaybackProbe {

  private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

  /** A mono sine tone as [TtsAudio] — same shape the synthesizer produces (Piper is 22.05 kHz). */
  private fun tone(seconds: Double, rate: Int = 22_050, hz: Double = 440.0): TtsAudio {
    val n = (seconds * rate).toInt()
    val samples = FloatArray(n) { i -> (0.25 * sin(2.0 * PI * hz * i / rate)).toFloat() }
    return TtsAudio(samples, rate)
  }

  private fun log(msg: String) = Log.i(TAG, msg)

  // ---- 1. the basic capability the JVM cannot answer ----

  @Test
  fun playsRealAudioToCompletionAtTheVoiceSampleRate() {
    val player = TtsPlayer(ctx)
    val audio = tone(1.0)
    val started = System.nanoTime()
    val result = player.play(audio)
    val elapsedMs = (System.nanoTime() - started) / 1_000_000

    log("play(1.0s @22050Hz) -> $result in ${elapsedMs}ms (audio ${audio.durationMs}ms)")
    assertEquals("22.05 kHz mono must be playable on this device", TtsPlayer.PlaybackResult.COMPLETED, result)
    // Proves drain() actually waits for the audio rather than returning when the buffer was handed
    // over — and that it doesn't overshoot into the deadline path.
    assertTrue("returned too early (${elapsedMs}ms) — drain did not wait", elapsedMs >= 800)
    assertTrue("returned too late (${elapsedMs}ms) — drain overshot", elapsedMs < audio.durationMs + 2_000)
  }

  // ---- 2. stop() mid-playback ----

  @Test
  fun stopMidPlaybackCancelsPromptly() {
    val player = TtsPlayer(ctx)
    val result = AtomicReference<TtsPlayer.PlaybackResult>()
    val done = CountDownLatch(1)

    Thread {
        result.set(player.play(tone(5.0)))
        done.countDown()
      }
      .start()

    Thread.sleep(700) // let it genuinely start producing sound
    val stoppedAt = System.nanoTime()
    player.stop()
    assertTrue("play() did not return within 1s of stop()", done.await(1, TimeUnit.SECONDS))
    val stopLatencyMs = (System.nanoTime() - stoppedAt) / 1_000_000

    log("stop() mid-playback -> ${result.get()} in ${stopLatencyMs}ms")
    assertEquals(TtsPlayer.PlaybackResult.CANCELLED, result.get())
    assertTrue("stop took ${stopLatencyMs}ms — should be near-immediate", stopLatencyMs < 500)
  }

  // ---- 3. supersede: the whole point of the generation token ----

  @Test
  fun secondPlaySupersedesTheFirst() {
    val player = TtsPlayer(ctx)
    val first = AtomicReference<TtsPlayer.PlaybackResult>()
    val firstDone = CountDownLatch(1)

    Thread {
        first.set(player.play(tone(5.0)))
        firstDone.countDown()
      }
      .start()
    Thread.sleep(700)

    // A second play must take over; the first must report CANCELLED, not FAILED.
    val second = player.play(tone(1.0))

    assertTrue("first play never returned", firstDone.await(2, TimeUnit.SECONDS))
    log("supersede: first=${first.get()} second=$second")
    assertEquals("superseded playback must not look like a failure", TtsPlayer.PlaybackResult.CANCELLED, first.get())
    assertEquals(TtsPlayer.PlaybackResult.COMPLETED, second)
  }

  // ---- 4. audio focus — the code no review pass could examine ----

  @Test
  fun losingAudioFocusStopsPlayback() {
    val player = TtsPlayer(ctx)
    val manager = ctx.getSystemService(AudioManager::class.java)
    assertNotNull("no AudioManager", manager)

    val result = AtomicReference<TtsPlayer.PlaybackResult>()
    val done = CountDownLatch(1)
    Thread {
        result.set(player.play(tone(5.0)))
        done.countDown()
      }
      .start()
    Thread.sleep(700)

    // Take focus out from under the player, the way a call or another media app would. The player's
    // listener should fire and stop playback.
    val competing =
      AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        )
        .setOnAudioFocusChangeListener {}
        .build()
    val granted = manager.requestAudioFocus(competing)
    log("competing AUDIOFOCUS_GAIN request -> $granted")
    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, granted)

    val returned = done.await(3, TimeUnit.SECONDS)
    manager.abandonAudioFocusRequest(competing)

    log("focus loss -> returned=$returned result=${result.get()}")
    assertTrue("playback did not stop when audio focus was lost", returned)
    assertEquals(TtsPlayer.PlaybackResult.CANCELLED, result.get())
  }

  // ---- 5. stop() while nothing is playing, and release() ----

  @Test
  fun stopAndReleaseAreSafeWhenIdle() {
    val player = TtsPlayer(ctx)
    player.stop()
    player.release()
    player.stop()
    // Still usable afterwards — release() is a stop, not a permanent teardown.
    assertEquals(TtsPlayer.PlaybackResult.COMPLETED, player.play(tone(0.3)))
    log("idle stop/release safe, player still usable")
  }

  // ---- 6. the real voice, end to end ----

  @Test
  fun speaksRealSynthesizedMarkdownWhenTheVoiceIsProvisioned() {
    val availability = SherpaTtsEngine.availability(ctx)
    log("SherpaTtsEngine availability = $availability")
    assumeTrue("voice not provisioned on this device — skipping", availability == TtsAvailability.READY)

    val markdown =
      """
      ## Node status

      The relay is **live** on `192.168.1.24:8443`.

      | field | value |
      |---|---|
      | backend | TPU |

      ```kotlin
      val ignored = "this must not be read aloud"
      ```
      """
        .trimIndent()

    val text = speakableText(markdown)
    log("speakable: \"$text\"")
    assertTrue("code block leaked into speech", !text.contains("ignored"))
    assertTrue("table punctuation not normalised: \"$text\"", !text.contains(" ,"))

    val synthStart = System.nanoTime()
    val audio = SherpaTtsEngine.synthesize(ctx, text)
    val synthMs = (System.nanoTime() - synthStart) / 1_000_000
    val rtf = synthMs.toDouble() / audio.durationMs.coerceAtLeast(1)
    log("synthesized ${audio.durationMs}ms of audio in ${synthMs}ms (RTF ${"%.3f".format(rtf)})")

    val result = TtsPlayer(ctx).play(audio)
    log("real-voice playback -> $result")
    assertEquals(TtsPlayer.PlaybackResult.COMPLETED, result)
  }

  private companion object {
    const val TAG = "RelaisSpeechProbe"
  }
}
