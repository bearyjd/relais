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

package cc.grepon.relais.imagegen

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device gate for Feature #16 PR-A — the process-isolated [ImageGenService] lifecycle, with NO
 * endpoint involved (`POST /v1/images/generations` is still a 501). Proves the PR-A invariants:
 *  1. the node can BIND the `:imagegen` service (a separate OS process),
 *  2. ONE generate returns a valid 512×512 PNG handed back by file path,
 *  3. a SECOND generate on a spent process is rejected (one-generate-per-process),
 *  4. a recoverable error replies `MSG_ERROR` rather than wedging, and
 *  5. the node can hard-kill the `:imagegen` pid and the process EXITS (crash-containment contract).
 *
 * Lives in `androidTestFull` — the sd.cpp/llmedge backend is full-flavor only.
 *
 * [bindGenerateOnceAndProcessExits] needs a Vulkan device + a pushed GGUF (SD-Turbo by default,
 * ~90 s/image). The other two tests exercise the IPC + error/CAS + self-kill paths and need NEITHER a
 * model NOR Vulkan (they fail fast at the model-readability check) — so they run on any device.
 *
 *   adb push sdturbo.gguf /data/local/tmp/relais/imagegen/sdturbo/sdturbo.gguf   # happy path only
 *   adb shell am instrument -w -e class cc.grepon.relais.imagegen.ImageGenServiceProbe \
 *     -e RELAIS_PROBE 1 cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 *   # optional: -e model_path /data/local/tmp/.../model.gguf   (override the default)
 *   # in another shell: adb logcat -s ImageGenService:*
 */
@RunWith(AndroidJUnit4::class)
class ImageGenServiceProbe {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val args = InstrumentationRegistry.getArguments()

  /** A single service → node reply. */
  private class Reply(val what: Int, val pngPath: String?, val error: String?, val pid: Int)

  /** Outcome of binding the service and sending [requestCount] generate requests. */
  private class Outcome(val replies: List<Reply>, val allReplied: Boolean, val processGone: Boolean)

  @Test
  fun bindGenerateOnceAndProcessExits() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    val modelPath = args.getString("model_path") ?: DEFAULT_MODEL_PATH
    assumeTrue(
      "GGUF model not readable at $modelPath — push one first (see the KDoc). Skipping.",
      File(modelPath).canRead(),
    )

    val outcome = runGenerates(listOf(generateRequest(modelPath)), timeoutS = GENERATE_TIMEOUT_S)

    assertTrue("no reply within ${GENERATE_TIMEOUT_S}s — generate hung or process died first", outcome.allReplied)
    val reply = outcome.replies.single()
    assertNull("generate reported an error: ${reply.error}", reply.error)

    val path = reply.pngPath
    assertTrue("MSG_RESULT carried no PNG path", !path.isNullOrBlank())
    val png = File(path!!)
    assertTrue("PNG file missing at $path", png.exists() && png.length() > 0)

    val bmp = requireNotNull(BitmapFactory.decodeFile(path)) { "PNG did not decode: $path" }
    assertEquals("width", 512, bmp.width)
    assertEquals("height", 512, bmp.height)
    assertTrue("image is blank (single flat color) — generation produced nothing", !isBlank(bmp))

    assertTrue("the :imagegen process did not exit after killProcess", outcome.processGone)
    png.delete()
  }

  @Test
  fun missingModelRepliesErrorAndProcessExits() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    // No Vulkan / model needed: the service fails fast at the readability check and replies MSG_ERROR.
    val bogus = "/data/local/tmp/relais/imagegen/__does_not_exist__.gguf"

    val outcome = runGenerates(listOf(generateRequest(bogus)), timeoutS = CONTROL_TIMEOUT_S)

    assertTrue("no reply within ${CONTROL_TIMEOUT_S}s", outcome.allReplied)
    val reply = outcome.replies.single()
    assertEquals("should be an error reply", ImageGenIpc.MSG_ERROR, reply.what)
    assertNull("no PNG should be produced on the error path", reply.pngPath)
    assertTrue("error should explain unreadable model, got: ${reply.error}", reply.error?.contains("not readable") == true)
    assertTrue("the :imagegen process did not exit after the error", outcome.processGone)
  }

  @Test
  fun secondGenerateOnSameProcessIsRejected() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    // Two generates on ONE process. Both use a bogus model (no real decode / no Vulkan needed): the
    // first is accepted by the CAS then fails at readability; the second hits CAS=true → "spent".
    val bogus = "/data/local/tmp/relais/imagegen/__does_not_exist__.gguf"

    val outcome = runGenerates(listOf(generateRequest(bogus), generateRequest(bogus)), timeoutS = CONTROL_TIMEOUT_S)

    assertTrue("expected two replies within ${CONTROL_TIMEOUT_S}s", outcome.allReplied)
    assertEquals("both should be errors", 2, outcome.replies.count { it.what == ImageGenIpc.MSG_ERROR })
    assertTrue(
      "exactly one reply should reject the second generate as 'spent', got: ${outcome.replies.map { it.error }}",
      outcome.replies.count { it.error?.contains("spent") == true } == 1,
    )
    assertTrue("the :imagegen process did not exit", outcome.processGone)
  }

  /**
   * Binds [ImageGenIpc.SERVICE_CLASS], sends one MSG_GENERATE per element of [requests] on the same
   * binder, awaits a reply for each, then hard-kills the `:imagegen` pid (the node's real teardown path)
   * and confirms the process exits. The reply Messenger runs on the main looper, which pumps
   * independently of this latch-blocked test thread.
   */
  private fun runGenerates(requests: List<Bundle>, timeoutS: Long): Outcome {
    val latch = CountDownLatch(requests.size)
    val replies = ArrayList<Reply>(requests.size)
    val replyTo = Messenger(Handler(Looper.getMainLooper()) { msg ->
      synchronized(replies) {
        replies.add(
          Reply(
            what = msg.what,
            pngPath = msg.data?.getString(ImageGenIpc.KEY_PNG_PATH),
            error = msg.data?.getString(ImageGenIpc.KEY_ERROR),
            pid = msg.data?.getInt(ImageGenIpc.KEY_PID, 0) ?: 0,
          )
        )
      }
      latch.countDown()
      true
    })

    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val outgoing = Messenger(binder)
        for (request in requests) {
          outgoing.send(Message.obtain(null, ImageGenIpc.MSG_GENERATE).apply {
            this.data = request
            this.replyTo = replyTo
          })
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {}
    }

    val intent = Intent().apply { setClassName(context, ImageGenIpc.SERVICE_CLASS) }
    assertTrue("bindService should initiate binding", context.bindService(intent, connection, Context.BIND_AUTO_CREATE))

    try {
      val allReplied = latch.await(timeoutS, TimeUnit.SECONDS)
      val collected = synchronized(replies) { replies.toList() }
      val pid = collected.firstOrNull { it.pid > 0 }?.pid ?: 0
      val processGone = if (pid > 0) {
        Process.killProcess(pid)
        awaitProcessGone()
      } else {
        false
      }
      return Outcome(collected, allReplied, processGone)
    } finally {
      runCatching { context.unbindService(connection) }
    }
  }

  private fun generateRequest(modelPath: String): Bundle = Bundle().apply {
    putString(ImageGenIpc.KEY_MODEL_PATH, modelPath)
    putString(ImageGenIpc.KEY_PROMPT, "a red apple on a wooden table")
    putInt(ImageGenIpc.KEY_WIDTH, 512)
    putInt(ImageGenIpc.KEY_HEIGHT, 512)
    putInt(ImageGenIpc.KEY_STEPS, 4)
    putLong(ImageGenIpc.KEY_SEED, 42L)
    putFloat(ImageGenIpc.KEY_CFG, 1.0f)
  }

  /** True if every sampled pixel is identical (a flat/blank canvas — generation failed silently). */
  private fun isBlank(bmp: Bitmap): Boolean {
    val step = maxOf(1, bmp.width / 16)
    val first = bmp.getPixel(0, 0)
    var x = 0
    while (x < bmp.width) {
      var y = 0
      while (y < bmp.height) {
        if (bmp.getPixel(x, y) != first) return false
        y += step
      }
      x += step
    }
    return true
  }

  /** Polls our own running processes until none named `…:imagegen` remain (or the timeout elapses). */
  private fun awaitProcessGone(): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val deadline = System.currentTimeMillis() + PROCESS_EXIT_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      val present = am.runningAppProcesses?.any { it.processName.endsWith(":imagegen") } ?: false
      if (!present) return true
      Thread.sleep(250)
    }
    return false
  }

  private companion object {
    const val DEFAULT_MODEL_PATH = "/data/local/tmp/relais/imagegen/sdturbo/sdturbo.gguf"
    const val GENERATE_TIMEOUT_S = 300L
    const val CONTROL_TIMEOUT_S = 30L
    const val PROCESS_EXIT_TIMEOUT_MS = 10_000L
  }
}
