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
import java.util.concurrent.Executor
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
   * Node-side watchdog. Kept BELOW [ImageGenService]'s hang-guard so the node reclaims a wedged process
   * first (and serves the next image from a fresh one). Generous: a COLD sd.cpp generate on a Tensor GPU
   * is ~5–6 min (measured on G4/Mali — UNet + tiled VAE decode), well above the warm ~90 s figure.
   */
  private const val WATCHDOG_MS = 720_000L
  private const val POLL_MS = 500L

  /** Best-effort: sweep PNGs left in the shared cache older than this (orphans from killed/raced jobs). */
  private const val SWEEP_AGE_MS = 10 * 60 * 1000L

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

  /** ONE consistent snapshot (single Vulkan + single provisioned check) — no isAvailable/canProvision TOCTOU. */
  override fun availability(context: Context): ImageGenAvailability =
    when {
      !vulkanAvailable() -> ImageGenAvailability.UNAVAILABLE
      ImageModelProvisioner.isProvisioned(context, selectedModel(context)) -> ImageGenAvailability.READY
      else -> ImageGenAvailability.PROVISIONING
    }

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
    // Defense-in-depth: the model path is operator-config-derived (NOT request-influenced) today, but
    // assert it stays under the provisioner root so a future request-influenced selector can't make the
    // :imagegen service open an arbitrary file.
    val root = ImageModelProvisioner.modelDir(app).canonicalFile.path
    val canonical = modelFile.canonicalFile.path
    require(canonical == root || canonical.startsWith(root + File.separator)) {
      "image model path escapes the provisioner root"
    }
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
   * the PNG, and ALWAYS hard-kills the `:imagegen` pid + unbinds in `finally`. The service acks its pid up
   * front ([ImageGenIpc.MSG_STARTED]) so the kill works on EVERY exit — reply, timeout, thermal-cancel, or
   * native hang — making the node (not just the service's own hang-guard self-kill) the primary reclaimer, so the
   * next image always gets a fresh process. Connection callbacks + replies land on a private
   * [HandlerThread], so the off-main wait never depends on the main looper being idle.
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
    sweepStaleCache(context)

    val latch = CountDownLatch(1)
    val resultPath = AtomicReference<String?>(null)
    val errorMsg = AtomicReference<String?>(null)
    val servicePid = AtomicInteger(-1)

    val replyThread = HandlerThread("relais-imagegen-reply").apply { start() }
    try {
      val replyHandler = Handler(
        replyThread.looper,
        Handler.Callback { msg ->
          // KEY_PID rides EVERY reply incl. the early MSG_STARTED ack, so the pid is known before the
          // long generate — the kill in `finally` then works on no-reply paths too.
          msg.data?.getInt(ImageGenIpc.KEY_PID, -1)?.takeIf { it > 0 }?.let { servicePid.set(it) }
          when (msg.what) {
            ImageGenIpc.MSG_STARTED -> Unit // pid captured above; keep waiting for the result
            ImageGenIpc.MSG_RESULT -> {
              resultPath.set(msg.data?.getString(ImageGenIpc.KEY_PNG_PATH))
              latch.countDown()
            }
            ImageGenIpc.MSG_ERROR -> {
              errorMsg.set(msg.data?.getString(ImageGenIpc.KEY_ERROR) ?: "generation failed")
              latch.countDown()
            }
            else -> {
              errorMsg.set("unexpected reply ${msg.what}")
              latch.countDown()
            }
          }
          true
        },
      )
      val replyMessenger = Messenger(replyHandler)
      val callbackExecutor = Executor { replyHandler.post(it) } // deliver conn callbacks off the main thread

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
      // bindService can register the connection even when it returns false, so unbind unconditionally in
      // `finally` (below) regardless of this return value.
      val bound = context.bindService(intent, Context.BIND_AUTO_CREATE, callbackExecutor, conn)
      try {
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
          try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) throw IllegalStateException("imagegen produced an empty PNG")
            Log.i(TAG, "generate ok: ${bytes.size} bytes")
            return bytes
          } finally {
            runCatching { file.delete() } // we own deletion (the service hands the PNG off by path)
          }
        }
        throw IllegalStateException(errorMsg.get() ?: "image generation produced no result")
      } finally {
        val pid = servicePid.get()
        if (pid > 0) runCatching { Process.killProcess(pid) }
        runCatching { context.unbindService(conn) } // unconditional: see the bindService note above
      }
    } finally {
      replyThread.quitSafely()
    }
  }

  /** Best-effort delete of stale PNGs in the shared `cacheDir/imagegen` (orphans from killed/raced jobs). */
  private fun sweepStaleCache(context: Context) {
    val dir = File(context.cacheDir, ImageGenIpc.PNG_CACHE_DIR)
    val cutoff = System.currentTimeMillis() - SWEEP_AGE_MS
    dir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() } }
  }
}
