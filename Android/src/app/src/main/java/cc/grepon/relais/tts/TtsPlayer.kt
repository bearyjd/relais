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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Plays synthesized speech out the device speaker (issue #211, in-app playback). The node's
 * `POST /v1/audio/speech` hands a container to an HTTP client; this is the in-app sibling that hands
 * the same samples to an [AudioTrack].
 *
 * Streaming (`MODE_STREAM`) rather than static, so [stop] is immediate on a long turn instead of
 * waiting out a fully-buffered clip.
 *
 * Threading contract: [play] **blocks** for the duration of the audio — call it on an IO dispatcher.
 * [stop] and [release] may be called from any thread (typically the main thread, from the UI) and
 * return immediately. Exactly one track is alive at a time: a second [play] supersedes the first,
 * which is how "tap SPEAK on another turn" interrupts cleanly.
 */
class TtsPlayer(context: Context) {

  /**
   * Why a [play] ended. The distinction matters to the caller: [CANCELLED] is the routine result of
   * a user stopping or superseding playback and must stay invisible, while [FAILED] means the device
   * refused the audio and the user needs to be told — otherwise a rejected sample rate looks
   * identical to a button that does nothing.
   */
  enum class PlaybackResult {
    /** The audio played to the end. */
    COMPLETED,

    /** Superseded by another [play], or stopped via [stop]/[release]. Not an error. */
    CANCELLED,

    /** The device could not play this audio (unsupported format, dead audio server, write error). */
    FAILED,
  }

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

  private val lock = Any()

  /** The track owned by the current/most-recent [play]. Identity doubles as the supersede token. */
  private var current: AudioTrack? = null

  /**
   * The audio-focus request held while anything is playing. Held at *player* level, not per-track, so
   * superseding one turn with another doesn't churn focus — and, critically, so the outgoing play's
   * teardown can't abandon the focus the incoming play is relying on.
   */
  private var focusHolder: AudioFocusRequest? = null

  /**
   * Synthesized-audio playback, start to finish. Returns when the audio has drained, or early when
   * superseded/stopped — see [PlaybackResult]. Never throws for cancellation.
   */
  fun play(audio: TtsAudio): PlaybackResult {
    val bytes = TtsWav.pcm16(audio.samples)
    if (bytes.isEmpty() || audio.sampleRate <= 0) return PlaybackResult.FAILED

    val track = buildTrack(audio.sampleRate)
    if (track == null) {
      // Uphold the supersede contract even on the failure path: a caller that relied on this play()
      // replacing the previous one must not be left with the old audio still running.
      stop()
      Log.w(TAG, "no AudioTrack for ${audio.sampleRate} Hz — playback unavailable on this device")
      return PlaybackResult.FAILED
    }

    // Focus is taken BEFORE the lock: requestAudioFocus is a binder round-trip to system_server, and
    // `lock` is what a main-thread stop() needs — holding it across IPC would block the UI.
    val focus = synchronized(lock) { focusHolder } ?: requestFocus()
    if (focus == null) {
      // Denied — something more important owns audio (typically a call). The platform contract is
      // to not play, and playing anyway would be worse here: a denied request registers no listener,
      // so nothing could tell us to stop.
      stop()
      Log.i(TAG, "audio focus denied — not playing")
      runCatching { track.release() }
      return PlaybackResult.FAILED
    }

    // Publish this track as the live one. A concurrent play() may have installed focus first; if so
    // ours is surplus and must be handed back rather than dropped.
    val surplusFocus =
      synchronized(lock) {
        stopLocked()
        current = track
        if (focusHolder == null) {
          focusHolder = focus
          null
        } else {
          focus.takeIf { it !== focusHolder }
        }
      }
    surplusFocus?.let { abandon(it) }

    val startedAt = System.nanoTime()
    return try {
      track.play()
      var offset = 0
      while (offset < bytes.size) {
        if (!isCurrent(track)) return PlaybackResult.CANCELLED
        val n = track.write(bytes, offset, minOf(CHUNK_BYTES, bytes.size - offset))
        if (n <= 0) {
          // A stopped/flushed track also reports an error here — only call it a failure if this
          // track is still the live one, i.e. nobody asked us to stop.
          return if (isCurrent(track)) {
            Log.w(TAG, "AudioTrack.write failed ($n) after $offset/${bytes.size} bytes")
            PlaybackResult.FAILED
          } else {
            PlaybackResult.CANCELLED
          }
        }
        offset += n
      }
      drain(track, bytes.size / BYTES_PER_FRAME, audio.durationMs, startedAt)
      if (isCurrent(track)) PlaybackResult.COMPLETED else PlaybackResult.CANCELLED
    } catch (t: IllegalStateException) {
      // The track was stopped/released underneath us by stop() — routine cancellation, not a failure.
      PlaybackResult.CANCELLED
    } finally {
      // Clear ownership and decide about focus in ONE critical section: focus is only abandoned when
      // no successor play has taken over, otherwise this teardown would cut the incoming turn off.
      val toAbandon =
        synchronized(lock) {
          if (current === track) current = null
          if (current == null) focusHolder.also { focusHolder = null } else null
        }
      toAbandon?.let { abandon(it) }
      runCatching { track.stop() }
      runCatching { track.release() }
    }
  }

  /** Stop playback now (idempotent, safe from any thread, safe when nothing is playing). */
  fun stop() {
    synchronized(lock) { stopLocked() }
  }

  /**
   * Stop playback and let the in-flight [play] tear its own track down. There is no separate resource
   * to free here: each [play] owns its [AudioTrack] and releases it in a `finally`, and [stop] is
   * what makes an in-flight one return promptly so that release actually happens.
   */
  fun release() = stop()

  /**
   * Wait out the buffered tail so the caller's "speaking" state clears when the audio actually ends,
   * not when the last byte was handed to the mixer. Polls rather than blocking so [stop] stays snappy.
   *
   * Bounded by a deadline derived from the audio's own duration: if the track stalls (focus loss,
   * route change, underrun) `playbackHeadPosition` stops advancing, and an unbounded wait would pin
   * this thread and leave the UI showing STOP with nothing playing.
   */
  private fun drain(track: AudioTrack, totalFrames: Int, expectedMs: Long, startedAt: Long) {
    // Deadline is measured from PLAYBACK start, not from here: the blocking writes above have
    // already consumed most of the audio's duration, so anchoring it here would grant a stalled
    // clip a second full duration of slack instead of the intended grace period.
    val deadline = startedAt + (expectedMs + DRAIN_GRACE_MS) * NANOS_PER_MS
    while (isCurrent(track)) {
      val played = runCatching { track.playbackHeadPosition }.getOrDefault(totalFrames)
      if (played >= totalFrames) return
      if (System.nanoTime() >= deadline) {
        Log.w(TAG, "drain timed out at $played/$totalFrames frames — treating playback as finished")
        return
      }
      Thread.sleep(DRAIN_POLL_MS)
    }
  }

  private fun isCurrent(track: AudioTrack): Boolean = synchronized(lock) { current === track }

  /**
   * Take transient audio focus so speech doesn't talk over music or through a call. Any loss stops
   * playback outright rather than ducking-and-resuming: resuming a spoken sentence from the middle
   * after an interruption is worse than simply stopping.
   *
   * Returns null when focus was denied. Callers must NOT hold [lock] — this is a binder call.
   */
  private fun requestFocus(): AudioFocusRequest? {
    val manager = audioManager ?: return null
    val request =
      AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes())
        .setOnAudioFocusChangeListener { change ->
          if (change != AudioManager.AUDIOFOCUS_GAIN) stop()
        }
        .build()
    val granted =
      runCatching { manager.requestAudioFocus(request) }
        .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) ==
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    return request.takeIf { granted }
  }

  private fun abandon(request: AudioFocusRequest) {
    runCatching { audioManager?.abandonAudioFocusRequest(request) }
  }

  /**
   * Stop the live track and clear it. Callers hold [lock]. Uses `pause` + `flush` rather than `stop`
   * so the buffered tail is discarded immediately instead of being played out to completion.
   */
  private fun stopLocked() {
    current?.let { track ->
      runCatching { track.pause() }
      runCatching { track.flush() }
    }
    current = null
  }

  private fun audioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()

  private fun buildTrack(sampleRate: Int): AudioTrack? {
    val minBuffer = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_MASK, ENCODING)
    if (minBuffer <= 0) return null // unsupported rate on this device
    return runCatching {
        AudioTrack.Builder()
          .setAudioAttributes(audioAttributes())
          .setAudioFormat(
            AudioFormat.Builder()
              .setEncoding(ENCODING)
              .setSampleRate(sampleRate)
              .setChannelMask(CHANNEL_MASK)
              .build()
          )
          .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES))
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build()
      }
      .getOrNull()
  }

  private companion object {
    /** Shared with SherpaTtsEngine so all TTS logging greps under one tag. */
    const val TAG = "RelaisTts"

    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO

    /** Mono 16-bit → one frame is one 2-byte sample. */
    const val BYTES_PER_FRAME = 2

    /** Write granularity: small enough that a [stop] lands within a few ms of the tap. */
    const val CHUNK_BYTES = 8 * 1024

    const val DRAIN_POLL_MS = 40L

    /** Slack over the audio's nominal duration before a stalled drain gives up. */
    const val DRAIN_GRACE_MS = 1_500L

    const val NANOS_PER_MS = 1_000_000L
  }
}
