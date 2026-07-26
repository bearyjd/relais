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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

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
class TtsPlayer {

  private val lock = Any()

  /** The track owned by the current/most-recent [play]. Identity doubles as the supersede token. */
  private var current: AudioTrack? = null

  /**
   * Synthesized-audio playback, start to finish. Returns when the audio has drained, or early when
   * superseded by another [play] / cancelled by [stop] — never throws for either of those.
   */
  fun play(audio: TtsAudio) {
    val bytes = TtsWav.pcm16(audio.samples)
    if (bytes.isEmpty() || audio.sampleRate <= 0) return

    val track = buildTrack(audio.sampleRate) ?: return

    // Supersede any in-flight playback, then publish this track as the live one. Both under the lock
    // so a concurrent stop() can never act on a half-installed track.
    synchronized(lock) {
      stopLocked()
      current = track
    }

    try {
      track.play()
      var offset = 0
      while (offset < bytes.size) {
        if (!isCurrent(track)) return // superseded or stopped — drop the rest
        val n = track.write(bytes, offset, minOf(CHUNK_BYTES, bytes.size - offset))
        if (n <= 0) return // error or stopped track; nothing useful left to do
        offset += n
      }
      drain(track, totalFrames = bytes.size / BYTES_PER_FRAME)
    } catch (t: IllegalStateException) {
      // The track was stopped/released underneath us by stop() — that's a normal cancellation here.
    } finally {
      synchronized(lock) {
        if (current === track) current = null
      }
      runCatching { track.stop() }
      runCatching { track.release() }
    }
  }

  /** Stop playback now (idempotent, safe from any thread, safe when nothing is playing). */
  fun stop() {
    synchronized(lock) { stopLocked() }
  }

  /** Stop and drop the player's resources. Call from the owner's teardown (e.g. `onCleared`). */
  fun release() = stop()

  /**
   * Wait out the buffered tail so the caller's "speaking" state clears when the audio actually ends,
   * not when the last byte was handed to the mixer. Polls rather than blocking so [stop] stays snappy.
   */
  private fun drain(track: AudioTrack, totalFrames: Int) {
    while (isCurrent(track)) {
      val played = runCatching { track.playbackHeadPosition }.getOrDefault(totalFrames)
      if (played >= totalFrames) return
      Thread.sleep(DRAIN_POLL_MS)
    }
  }

  private fun isCurrent(track: AudioTrack): Boolean = synchronized(lock) { current === track }

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

  private fun buildTrack(sampleRate: Int): AudioTrack? {
    val minBuffer = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_MASK, ENCODING)
    if (minBuffer <= 0) return null // unsupported rate on this device
    return runCatching {
        AudioTrack.Builder()
          .setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build()
          )
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
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO

    /** Mono 16-bit → one frame is one 2-byte sample. */
    const val BYTES_PER_FRAME = 2

    /** Write granularity: small enough that a [stop] lands within a few ms of the tap. */
    const val CHUNK_BYTES = 8 * 1024

    const val DRAIN_POLL_MS = 40L
  }
}
