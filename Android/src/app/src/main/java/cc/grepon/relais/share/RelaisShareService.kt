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

package cc.grepon.relais.share

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cc.grepon.relais.R
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.core.RelaisInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RelaisShareService"
private const val CHANNEL_ID = "relais_share_result"
private const val PROGRESS_NOTIFICATION_ID = 0x52454C53 // "RELS" — foreground/progress slot
private const val RESULT_NOTIFICATION_ID = 0x52454C54 // "RELT" — stable result slot, so results don't stack
private const val EXTRA_PAYLOAD = "cc.grepon.relais.share.PAYLOAD"
private const val EXTRA_SUBJECT = "cc.grepon.relais.share.SUBJECT" // optional EXTRA_SUBJECT for the image path
private const val EXTRA_CAPTION = "cc.grepon.relais.share.CAPTION" // optional EXTRA_TEXT alongside an image
private const val EXTRA_SYSTEM = "cc.grepon.relais.share.SYSTEM" // optional system-prompt override (NFC #15 workflows)
private const val RESULT_CAP = 1_000 // bound the notification body (the shade is a public surface)
private const val CLIP_LABEL = "Relais"

/** Concise built-in system prompt used when the operator hasn't set an override (Feature #1). */
const val DEFAULT_SHARE_SYSTEM = "Summarize or answer the shared text clearly and concisely."

/**
 * Short-lived foreground service that runs ONE share-sheet inference off the trampoline's lifecycle,
 * so a 30–120s decode survives [RelaisShareActivity] finishing immediately. It is a CONSUMER of the
 * already-resident engine: it re-asserts [RelaisInference.isReady] (defense in depth) and never
 * cold-starts. On success it posts a DESIGN.md-styled result notification (capped) AND copies the
 * full result to the clipboard; on error it posts an error notification rather than crashing or
 * swallowing. `dataSync` foreground type; a stable result notification id so repeated shares replace
 * rather than stack.
 */
class RelaisShareService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // The most recent startId. The service is a singleton: every start lands on this instance, so a
  // rejected/duplicate start must never tear it down while a real decode runs (that silently loses the
  // result). We only ever stop when idle, via [stopIfIdle].
  @Volatile private var latestStartId = 0

  // [stopIfIdle] runs ONLY on the main thread (where onStartCommand is delivered), so the `inFlight`
  // check and the stop are serialized with every start and can never race a concurrent one.
  private val mainHandler = Handler(Looper.getMainLooper())

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    latestStartId = startId
    ensureChannel(this)

    val textPayload = intent?.getStringExtra(EXTRA_PAYLOAD)?.takeIf { it.isNotBlank() }
    val imageUris = readGrantedImageUris(intent) // #13 OCR: granted content:// URIs from the trampoline
    val subject = intent?.getStringExtra(EXTRA_SUBJECT)
    val caption = intent?.getStringExtra(EXTRA_CAPTION) // EXTRA_TEXT shared alongside the image, if any
    val systemOverride = intent?.getStringExtra(EXTRA_SYSTEM)?.takeIf { it.isNotBlank() } // NFC #15 template system
    val hasWork = textPayload != null || imageUris.isNotEmpty()

    // Enter the foreground BEFORE the decode. If the OS rejects the FGS start — possible on a cross-app
    // share from a backgrounded app, or under GrapheneOS's stricter FGS rules — do NOT let the uncaught
    // exception crash the service: that silently drops the share. Post an error result and stop (only if
    // idle, so a decode already in flight on this singleton instance is never torn down).
    val foregrounded =
      runCatching { startForeground(PROGRESS_NOTIFICATION_ID, buildProgress(), foregroundType()) }
        .onFailure { Log.w(TAG, "OS rejected the foreground-service start; reporting unavailable", it) }
        .isSuccess
    if (!foregrounded) {
      // Report only when idle: if a prior decode is in flight on this singleton it OWNS the single
      // result-notification slot, so don't clobber its eventual result with an "unavailable" notice.
      if (hasWork && !inFlight.get()) {
        postResult(title = "Relais · unavailable", text = "Couldn't start the share service. Try again with Relais in the foreground.")
      }
      stopIfIdle()
      return START_NOT_STICKY
    }

    if (!hasWork) {
      // Nothing usable reached the service (shouldn't happen — the trampoline gates on it). Stop
      // cleanly (only if idle) without an inference or a misleading result.
      Log.i(TAG, "no payload; stopping if idle")
      stopIfIdle()
      return START_NOT_STICKY
    }

    // Drop a second concurrent share instead of stacking another decode on the engine lock. Do NOT stop
    // the service here: a decode is in flight (that's why the CAS failed), and stopSelf on the latest
    // startId would cancel it — silently losing the running share's result.
    if (!inFlight.compareAndSet(false, true)) {
      Log.i(TAG, "a share inference is already running; dropping this start")
      postResult(title = "Relais · busy", text = "A share is already being processed.")
      stopIfIdle() // no-op while a decode runs; only stops a genuinely idle service
      return START_NOT_STICKY
    }

    scope.launch {
      try {
        // Re-assert readiness inside the service: the trampoline gated on isReady() at hand-off, but
        // the node could have stopped before this runs — never let the run cold-start the engine.
        if (!RelaisInference.isReady()) {
          Log.i(TAG, "engine not resident at run time; skipping share inference")
          postResult(title = "Relais · node not running", text = "Start the node, then share again.")
          return@launch
        }
        // Resolve the prompt: the shared text, or — for an image share (#13) — the on-device OCR of the
        // shared image(s), with any caption (EXTRA_TEXT shared alongside) prefixed. OCR runs here (off the
        // trampoline) because it is async; the post-OCR emptiness check is the EMPTY gate the activity
        // couldn't apply before the text was known.
        val payload = textPayload ?: run {
          val ocr = ImageTextRecognizer.recognize(applicationContext, imageUris)
          val blocks = buildList {
            caption?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            addAll(ocr)
          }
          extractSharedText(text = null, subject = subject, extraTexts = blocks, maxChars = MAX_SHARE_CHARS)
        }
        if (payload.isNullOrBlank()) {
          postResult(title = "Relais · no text found", text = "Couldn't read any text from the shared image.")
          return@launch
        }
        val system = systemOverride ?: RelaisConfig.shareSystemPrompt(applicationContext) ?: DEFAULT_SHARE_SYSTEM
        // No caller-supplied timeout here (unlike the automation ABI's withTimeout(job.timeoutMs)): the
        // share sheet has no timeout param, and the decode is already bounded by RelaisEngine's internal
        // wait, so a wedged decode releases (then the next share proceeds) rather than hanging forever.
        val answer = runCatching {
          RelaisInference.completeText(applicationContext, prompt = payload, system = system)
        }.getOrElse { t ->
          if (t is kotlinx.coroutines.CancellationException) throw t // never swallow cancellation
          if (t is RelaisInference.NodeNotReadyException) {
            Log.i(TAG, "node went down mid-run", t)
            postResult(title = "Relais · node not running", text = "Start the node, then share again.")
          } else {
            Log.e(TAG, "share inference failed", t) // never silently swallow
            postResult(title = "Relais · error", text = t.message ?: "Inference failed.")
          }
          return@launch
        }
        copyToClipboard(answer) // full result on the clipboard
        postResult(title = "Relais · result", text = answer.take(RESULT_CAP)) // capped on the shade
      } finally {
        inFlight.set(false) // always release the single-flight latch, incl. on cancellation
        // Run the stop decision on the main thread so it's serialized with onStartCommand: a start
        // landing concurrently with this teardown is then either fully observed (we don't stop) or not
        // yet begun (we stop an idle service it simply restarts) — never a cancelled live decode.
        mainHandler.post { stopIfIdle() }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    inFlight.set(false) // defensive: never leave the latch stuck if torn down mid-run
    // Best-effort: coroutine cancellation only stops the decode at the NEXT token callback. It does NOT
    // interrupt the engine's in-progress native decode — RelaisEngine holds its lock across an internal
    // bounded wait, not released by scope.cancel() (litertlm exposes no mid-decode cancel).
    scope.cancel()
    super.onDestroy()
  }

  /**
   * Stops the service, but ONLY when no decode is in flight — the `inFlight` check is the ENTIRE guard
   * against tearing down a live decode (a busy/duplicate start's call here is a no-op while a decode
   * runs). `latestStartId` is passed so `stopSelf(id)` matches the most-recent delivered start and
   * therefore actually stops an idle service — a stale id would no-op and leak the foreground service.
   * MUST run on the main thread (called directly from `onStartCommand`, or posted via [mainHandler]),
   * so the `inFlight` read + `stopSelf` are serialized with every incoming start.
   */
  private fun stopIfIdle() {
    if (!inFlight.get()) stopSelf(latestStartId)
  }

  /** The granted `content://` image URIs from an image share (#13), read from the intent's ClipData. */
  private fun readGrantedImageUris(intent: Intent?): List<Uri> {
    val clip = intent?.clipData ?: return emptyList()
    return (0 until clip.itemCount)
      .mapNotNull { clip.getItemAt(it).uri }
      .filter { it.scheme == "content" }
  }

  private fun copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text)) }
      .onFailure { Log.w(TAG, "failed to copy share result to clipboard", it) }
  }

  /** Posts the result; silently no-ops if POST_NOTIFICATIONS isn't granted (runtime perm is API 33+). */
  private fun postResult(title: String, text: String) {
    val ctx = applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      Log.i(TAG, "POST_NOTIFICATIONS not granted; share result not shown")
      return
    }
    val notification =
      NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSmallIcon(R.drawable.ic_relais_tile)
        .setColor(0xFFFFB000.toInt()) // DESIGN.md signal amber accent
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // shade content stays off the lock screen
        .setAutoCancel(true)
        .build()
    runCatching { NotificationManagerCompat.from(ctx).notify(RESULT_NOTIFICATION_ID, notification) }
      .onFailure { Log.w(TAG, "failed to post share result", it) }
  }

  private fun buildProgress(): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Relais · working")
      .setContentText("Processing shared content on-device…")
      .setSmallIcon(R.drawable.ic_relais_tile)
      .setColor(0xFFFFB000.toInt()) // DESIGN.md signal amber accent
      .setOngoing(true)
      .setProgress(0, 0, true) // indeterminate
      .build()

  private fun foregroundType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    else 0

  private fun ensureChannel(ctx: Context) {
    val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
      mgr.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Relais share results", NotificationManager.IMPORTANCE_DEFAULT)
          .apply { description = "Results from sharing text into the Relais on-device node" }
      )
    }
  }

  companion object {
    // Process-global single-flight: one share at a time (OCR + decode), so a malicious app looping the
    // exported trampoline can't stack OCR passes + 30-120s decodes on the engine lock. (The tile/widget
    // paths run through their own WorkManager workers, not this service, and coalesce independently.)
    private val inFlight = AtomicBoolean(false)

    /** Builds the start intent carrying [payload] for the text share run. */
    fun startIntent(context: Context, payload: String): Intent =
      Intent(context, RelaisShareService::class.java).putExtra(EXTRA_PAYLOAD, payload)

    /**
     * Like [startIntent] but with a system-prompt override — used by NFC workflows (#15) to run a
     * resolved prompt template's system prompt instead of the share default.
     */
    fun promptIntent(context: Context, payload: String, system: String?): Intent =
      startIntent(context, payload).apply {
        system?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_SYSTEM, it) }
      }

    /**
     * Builds the start intent for an image share (#13 OCR): carries the image [uris] as ClipData and
     * sets FLAG_GRANT_READ_URI_PERMISSION so this (un-exported) service can read them for OCR. The grant
     * to the service is independent of the trampoline's, so it survives the activity finishing.
     */
    fun imageIntent(context: Context, uris: List<Uri>, subject: String?, caption: String?): Intent {
      val intent = Intent(context, RelaisShareService::class.java)
      subject?.takeIf { it.isNotBlank() }?.let { intent.putExtra(EXTRA_SUBJECT, it) }
      caption?.takeIf { it.isNotBlank() }?.let { intent.putExtra(EXTRA_CAPTION, it) }
      if (uris.isNotEmpty()) {
        val clip = ClipData.newRawUri(CLIP_LABEL, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      return intent
    }
  }
}
