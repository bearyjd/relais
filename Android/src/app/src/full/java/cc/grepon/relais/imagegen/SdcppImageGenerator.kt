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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import cc.grepon.relais.RelaisConfig
import io.aatricks.llmedge.LLMEdge
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * FULL-FLAVOR [RelaisImageGenerator]: stable-diffusion.cpp (Vulkan, via llmedge) behind the
 * process-isolated [ImageGenService]. `isAvailable` = Vulkan present AND the selected model on disk;
 * `generate` runs ONE generate in a fresh `:imagegen` process and hard-kills it after — the only sd.cpp
 * primitive proven stable on-device (see `docs/images-generations-api.md`). degoogled registers nothing
 * (its [ImageGenRegistration] is a no-op), so `POST /v1/images/generations` stays 501 there.
 */
object SdcppImageGenerator : RelaisImageGenerator {

  private const val TAG = "RelaisSdcppImageGen"

  /**
   * Node-side watchdog. Kept BELOW [ImageGenService]'s 300 s hang-guard so the node reclaims a wedged
   * process first (and serves the next image from a fresh one). Generous vs the ~90 s SD-Turbo / ~184 s
   * SD-1.5 single-generate ceiling.
   */
  private const val WATCHDOG_MS = 280_000L
  private const val POLL_MS = 500L

  private val provisioning = AtomicBoolean(false)

  private fun selectedModel(context: Context): ImageModel =
    imageModelById(
      RelaisConfig.imageModelId(context),
      RelaisConfig.imageModelUrl(context),
      RelaisConfig.imageModelSha(context),
    ) ?: IMAGE_MODEL_TURBO

  private fun vulkanAvailable(): Boolean = runCatching { LLMEdge.isVulkanAvailable() }.getOrDefault(false)

  override fun isAvailable(context: Context): Boolean =
    vulkanAvailable() && ImageModelProvisioner.isProvisioned(context, selectedModel(context))

  override fun canProvision(context: Context): Boolean =
    vulkanAvailable() && !ImageModelProvisioner.isProvisioned(context, selectedModel(context))

  override fun ensureProvisioningStarted(context: Context) {
    if (!provisioning.compareAndSet(false, true)) return // a download is already in flight
    val app = context.applicationContext
    thread(name = "relais-imageprov", isDaemon = true) {
      try {
        val m = selectedModel(app)
        Log.i(TAG, "provisioning image model ${m.id}")
        ImageModelProvisioner.ensure(app, m, token = RelaisConfig.hfToken(app))
        Log.i(TAG, "image model ${m.id} provisioned")
      } catch (e: Exception) {
        Log.e(TAG, "image model provisioning failed: ${e.message}")
      } finally {
        provisioning.set(false)
      }
    }
  }

  override fun generate(
    context: Context,
    prompt: String,
    steps: Int,
    seed: Long?,
    shouldCancel: () -> Boolean,
  ): ByteArray {
    val app = context.applicationContext
    val model = selectedModel(app)
    val modelFile = ImageModelProvisioner.modelFile(app, model)
    require(modelFile.canRead()) { "image model not provisioned: ${modelFile.path}" }
    return runOneGenerate(
      context = app,
      modelPath = modelFile.absolutePath,
      prompt = prompt,
      steps = steps,
      seed = seed ?: Random.nextLong(),
      cfg = model.cfg,
      shouldCancel = shouldCancel,
    )
  }

  /**
   * Binds [ImageGenService], dispatches ONE generate, waits (watchdog + thermal cancel), reads + deletes
   * the PNG, and ALWAYS hard-kills the `:imagegen` pid + unbinds in `finally`. The service self-kills as a
   * backstop; the node is the primary killer so the next image gets a fresh process. Runs off-main
   * (the HTTP worker thread, behind the admission gate); the reply lands on a private [HandlerThread], so
   * the wait never depends on the main looper being idle.
   */
  private fun runOneGenerate(
    context: Context,
    modelPath: String,
    prompt: String,
    steps: Int,
    seed: Long,
    cfg: Float,
    shouldCancel: () -> Boolean,
  ): ByteArray {
    val latch = CountDownLatch(1)
    val resultPath = AtomicReference<String?>(null)
    val errorMsg = AtomicReference<String?>(null)
    val servicePid = AtomicInteger(-1)

    val replyThread = HandlerThread("relais-imagegen-reply").apply { start() }
    val replyHandler = Handler(
      replyThread.looper,
      Handler.Callback { msg ->
        servicePid.set(msg.data?.getInt(ImageGenIpc.KEY_PID, -1) ?: -1)
        when (msg.what) {
          ImageGenIpc.MSG_RESULT -> resultPath.set(msg.data?.getString(ImageGenIpc.KEY_PNG_PATH))
          ImageGenIpc.MSG_ERROR -> errorMsg.set(msg.data?.getString(ImageGenIpc.KEY_ERROR) ?: "generation failed")
          else -> errorMsg.set("unexpected reply ${msg.what}")
        }
        latch.countDown()
        true
      },
    )
    val replyMessenger = Messenger(replyHandler)

    var bound = false
    val conn = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        if (binder == null) {
          errorMsg.set("imagegen bound with null binder")
          latch.countDown()
          return
        }
        val req = Message.obtain(null, ImageGenIpc.MSG_GENERATE).apply {
          data = Bundle().apply {
            putString(ImageGenIpc.KEY_MODEL_PATH, modelPath)
            putString(ImageGenIpc.KEY_PROMPT, prompt)
            putInt(ImageGenIpc.KEY_WIDTH, 512)
            putInt(ImageGenIpc.KEY_HEIGHT, 512)
            putInt(ImageGenIpc.KEY_STEPS, steps)
            putLong(ImageGenIpc.KEY_SEED, seed)
            putFloat(ImageGenIpc.KEY_CFG, cfg)
          }
          replyTo = replyMessenger
        }
        try {
          Messenger(binder).send(req)
        } catch (e: RemoteException) {
          errorMsg.set("send failed: ${e.message}")
          latch.countDown()
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        // The :imagegen process died. If we hadn't already gotten a reply, that's a native crash
        // (SIGSEGV) — surface it so the route fails cleanly instead of waiting out the watchdog.
        if (resultPath.get() == null && errorMsg.get() == null) {
          errorMsg.set("imagegen process exited before replying")
          latch.countDown()
        }
      }
    }

    val intent = Intent().apply {
      component = ComponentName(context.packageName, ImageGenIpc.SERVICE_CLASS)
    }
    try {
      bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
      if (!bound) throw IllegalStateException("could not bind :imagegen service")

      val deadline = SystemClock.elapsedRealtime() + WATCHDOG_MS
      while (true) {
        if (latch.await(POLL_MS, TimeUnit.MILLISECONDS)) break
        if (shouldCancel()) {
          errorMsg.compareAndSet(null, "image generation cancelled (thermal)")
          break
        }
        if (SystemClock.elapsedRealtime() > deadline) {
          errorMsg.compareAndSet(null, "image generation timed out")
          break
        }
      }

      val path = resultPath.get()
      if (path != null) {
        val file = File(path)
        val bytes = file.readBytes()
        file.delete()
        if (bytes.isEmpty()) throw IllegalStateException("imagegen produced an empty PNG")
        Log.i(TAG, "generate ok: ${bytes.size} bytes")
        return bytes
      }
      throw IllegalStateException(errorMsg.get() ?: "image generation produced no result")
    } finally {
      val pid = servicePid.get()
      if (pid > 0) runCatching { Process.killProcess(pid) }
      if (bound) runCatching { context.unbindService(conn) }
      replyThread.quitSafely()
    }
  }
}
