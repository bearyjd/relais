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

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import io.aatricks.llmedge.image.ImageClient
import io.aatricks.llmedge.image.ImageGenerationRequest
import io.aatricks.llmedge.model.ModelSpec
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-isolated, single-shot Stable-Diffusion (sd.cpp/Vulkan via llmedge) image generator —
 * feature #16, FULL FLAVOR ONLY. Declared `android:process=":imagegen"` so it runs in its own OS
 * process: it loads the model, does EXACTLY ONE generate, writes the PNG, replies the path, and dies.
 *
 * Why one-shot-per-process is non-negotiable (proven on-device, see `docs/images-generations-api.md`):
 * every sd.cpp operation past the first generate broke — reuse → SIGSEGV, close+recreate → deadlock,
 * the LLMEdge facade → hangs. The ONLY stable primitive is a direct [ImageClient.create] doing a
 * single generate. So this service is stable BY CONSTRUCTION: each image is a fresh process's
 * first-and-only generate, and any native crash/hang is contained to a disposable process the node
 * hard-kills. It NEVER reuses a client, NEVER close+recreates, NEVER touches the facade.
 *
 * Teardown ownership: the NODE (main process) is the primary killer — it gets [ImageGenIpc.KEY_PID]
 * back and hard-kills this process on reply or watchdog timeout (PR-C). This service self-kills as a
 * backstop on two paths: (a) a short delay after replying, and (b) a wall-clock hang-guard armed when
 * the job is accepted, so a native load that wedges (uncancellable from Kotlin) still cannot keep
 * `:imagegen` alive indefinitely even if the node's killer is missing.
 *
 * IPC: bound Messenger (see [ImageGenIpc]). Control data crosses the binder; the PNG is handed back by
 * file path (a 512×512 PNG can exceed the ~1 MB binder limit).
 *
 * NOT yet wired to the endpoint — `POST /v1/images/generations` stays a 501 until PR-C registers the
 * [RelaisImageGenerator] impl that drives this service.
 */
class ImageGenService : Service() {

  // One generate per process, ever. CAS-guards against a second MSG_GENERATE reaching this process.
  private val started = AtomicBoolean(false)

  // Heavy generate runs here (off the binder/main thread). The process dies after one generate, so the
  // scope is never explicitly torn down for reuse — process death frees the native runtime.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // Single main-looper handler: routes incoming binder messages AND schedules the self-kill timers.
  private val handler = Handler(Looper.getMainLooper(), Handler.Callback { msg -> onMessage(msg) })
  private val incoming = Messenger(handler)

  // SIGKILLs this whole process (synchronous for self). Idempotent: whichever timer fires first wins.
  private val killSelf = Runnable {
    Log.i(TAG, "self-kill (process spent) pid=${Process.myPid()}")
    stopSelf()
    Process.killProcess(Process.myPid())
  }

  override fun onBind(intent: Intent?): IBinder = incoming.binder

  private fun onMessage(msg: Message): Boolean {
    if (msg.what != ImageGenIpc.MSG_GENERATE) return false
    val replyTo: Messenger? = msg.replyTo
    val request = msg.data ?: Bundle()

    if (!started.compareAndSet(false, true)) {
      // Contract violation: this process already did (or is doing) its one generate.
      reply(replyTo, ImageGenIpc.MSG_ERROR, error = "one generate per process; this process is spent")
      return true
    }

    // Hang-guard: arm a wall-clock self-kill the moment the job is accepted. A native load that wedges
    // can't be cancelled cooperatively from Kotlin (the coroutine never reaches a suspension point), so
    // only an unconditional timer guarantees reclamation. Normal completion replaces this with the
    // shorter post-reply kill (see scheduleSelfKill).
    handler.postDelayed(killSelf, HANG_GUARD_MS)

    scope.launch {
      try {
        val pngPath = generateOnce(request)
        Log.i(TAG, "generate complete → $pngPath")
        reply(replyTo, ImageGenIpc.MSG_RESULT, pngPath = pngPath)
      } catch (c: CancellationException) {
        throw c
      } catch (e: Exception) {
        // A recoverable error (bad model, invalid params, llmedge load failure) → report it. A native
        // SIGSEGV is NOT catchable here — that's the whole point of process isolation: the process dies
        // and the node's watchdog (or the hang-guard) reclaims it. No PNG was written on this path.
        Log.e(TAG, "generate failed", e)
        reply(replyTo, ImageGenIpc.MSG_ERROR, error = e.message ?: e.javaClass.simpleName)
      } finally {
        scheduleSelfKill()
      }
    }
    return true
  }

  /** The one-and-only generate. Direct [ImageClient.create] → one [ImageClient.generate]. */
  // DEPRECATION is deliberate: llmedge deprecates ImageClient.create in favour of the
  // `LLMEdge.create(...).image` facade, but that facade is exactly the path the on-device verdict
  // proved HANGS on the first generate. The direct factory is the only stable primitive (the AAR's own
  // javadoc keeps it "available for advanced construction and tests"). Do not "fix" this to the facade.
  @Suppress("DEPRECATION")
  private suspend fun generateOnce(request: Bundle): String {
    // The model path comes from the (trusted, same-UID, in-package) node over an exported=false binder,
    // so it is not an attacker boundary today. PR-C MUST still constrain it to the provisioner's model
    // root once the path becomes HTTP-request-influenced (the on-device probe pushes to /data/local/tmp,
    // so a strict root check can't live here yet).
    val modelPath = request.getString(ImageGenIpc.KEY_MODEL_PATH)
      ?: throw IllegalArgumentException("missing ${ImageGenIpc.KEY_MODEL_PATH}")
    val modelFile = File(modelPath)
    if (!modelFile.canRead()) {
      throw IllegalArgumentException("model not readable: $modelPath")
    }
    val prompt = request.getString(ImageGenIpc.KEY_PROMPT).orEmpty()
    val width = request.getInt(ImageGenIpc.KEY_WIDTH, 512)
    val height = request.getInt(ImageGenIpc.KEY_HEIGHT, 512)
    val steps = request.getInt(ImageGenIpc.KEY_STEPS, 4)
    val seed = request.getLong(ImageGenIpc.KEY_SEED, 0L)
    val cfg = request.getFloat(ImageGenIpc.KEY_CFG, 1.0f)

    // Log dimensions/knobs but NOT the prompt (a user prompt may be sensitive; logcat is observable).
    Log.i(TAG, "loading model + generating: ${width}x$height steps=$steps cfg=$cfg seed=$seed promptLen=${prompt.length}")

    // The stable primitive: a direct ImageClient.create doing exactly ONE generate. Never the LLMEdge
    // facade (hangs), never reused, never closed+recreated (deadlocks). We deliberately do NOT call
    // client.close()/cancelGeneration(): process death (the kill path) is the reclaim mechanism, and an
    // in-process close() is an untested teardown that risks the very deadlock the verdict warns about.
    val client = ImageClient.create(applicationContext, scope)
    val bitmap: Bitmap = client.generate(
      ImageGenerationRequest(
        prompt = prompt,
        width = width,
        height = height,
        steps = steps,
        cfgScale = cfg,
        seed = seed,
        model = ModelSpec.localFile(modelFile),
      )
    )
    return writePng(bitmap)
  }

  /** Writes [bitmap] as a PNG into the shared `cacheDir/imagegen/<uuid>.png` and returns its path. */
  private fun writePng(bitmap: Bitmap): String {
    val dir = File(cacheDir, ImageGenIpc.PNG_CACHE_DIR).apply { mkdirs() }
    val out = File(dir, "${UUID.randomUUID()}.png")
    FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return out.absolutePath
  }

  private fun reply(replyTo: Messenger?, what: Int, pngPath: String? = null, error: String? = null) {
    if (replyTo == null) {
      Log.w(TAG, "no replyTo Messenger; node cannot receive what=$what")
      deleteIfPresent(pngPath) // orphan cleanup: the node would have been the deleter.
      return
    }
    val data = Bundle().apply {
      putInt(ImageGenIpc.KEY_PID, Process.myPid())
      pngPath?.let { putString(ImageGenIpc.KEY_PNG_PATH, it) }
      error?.let { putString(ImageGenIpc.KEY_ERROR, it) }
    }
    try {
      replyTo.send(Message.obtain(null, what).apply { this.data = data })
    } catch (e: RemoteException) {
      // The node died/unbound before we replied. The node owns the kill; we self-kill regardless. The
      // PNG it would have read+deleted is now orphaned — delete it here so failures don't fill the cache.
      Log.w(TAG, "reply failed (node gone)", e)
      deleteIfPresent(pngPath)
    }
  }

  private fun deleteIfPresent(path: String?) {
    path?.let { runCatching { File(it).delete() } }
  }

  /**
   * Self-kill AFTER the reply has had time to flush across the (async, oneway) binder transaction.
   * Cancels the hang-guard and re-arms a short delay. Belt-and-suspenders: the node hard-kills this pid
   * on reply/timeout too (PR-C) — that is the primary teardown; this short delay is the backstop.
   */
  private fun scheduleSelfKill() {
    handler.removeCallbacks(killSelf)
    handler.postDelayed(killSelf, SELF_KILL_DELAY_MS)
  }

  private companion object {
    const val TAG = "ImageGenService"
    const val SELF_KILL_DELAY_MS = 2_000L
    // Wall-clock backstop for a wedged native load. Generous (well above the ~90 s SD-Turbo / ~180 s
    // SD-1.5 realistic single-generate ceiling) — this catches a true hang, not a slow-but-progressing
    // decode. The node's tighter watchdog (PR-C) is the normal-operation timeout.
    const val HANG_GUARD_MS = 300_000L
  }
}
