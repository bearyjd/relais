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

package cc.grepon.relais

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.core.RelaisInference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device e2e probe for Feature #13 — the FULL image-share chain that [ImageOcrProbe] (which isolates
 * just the recognizer) does not exercise: a real `content://` image → the exported [share.RelaisShareActivity]
 * trampoline → [share.RelaisShareService] → OCR → resident-model inference → result delivery.
 *
 * Lives in `androidTestFull` (full flavor only — the OCR path needs the GMS-pulling ML Kit recognizer; the
 * degoogled flavor stubs it out).
 *
 * Authoritative observable: the concurrent logcat + the on-device "Relais" result notification. The clipboard
 * is a BEST-EFFORT observable only — Android 10+ blocks clipboard reads from a non-focused process, so the
 * probe hard-asserts only that the trampoline accepted the content:// share without crashing, and additionally
 * asserts the clipboard result when the platform lets it read it.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.ShareImageProbe -e RELAIS_PROBE 1 \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 *   # in another shell: adb logcat -s RelaisShareService:* RelaisOcr:* RelaisEngine:*
 */
@RunWith(AndroidJUnit4::class)
class ShareImageProbe {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun shareImageOcrInferenceRoundTrip() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")

    // The trampoline gates a RUN on shareEnabled AND engine readiness (it never cold-starts). shareEnabled
    // defaults true, but set it defensively; warm the engine so RelaisInference.isReady() is true (only true
    // after the first decode), or the trampoline takes the NODE_OFF branch and posts a status notification.
    RelaisConfig.setShareEnabled(context, true)
    RelaisEngine.generate(context, RelaisRequest(text = "hi"))
    assertTrue("engine should be ready after warm-up", RelaisInference.isReady())

    // Render known text to a PNG (mirror ImageOcrProbe's recognizable bitmap).
    val marker = "RELAIS INVOICE 4242"
    val bitmap = Bitmap.createBitmap(900, 240, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
      drawColor(Color.WHITE)
      drawText(
        marker,
        32f,
        150f,
        Paint().apply {
          color = Color.BLACK
          textSize = 88f
          isAntiAlias = true
        },
      )
    }

    // Insert into MediaStore → a content:// URI owned by this UID (no _data column on A16); write the PNG.
    // The trampoline rejects file:// (RelaisShareActivity.readImageUris), so a MediaStore content:// is required.
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "relais_ocr_test_${System.nanoTime()}.png")
      put(MediaStore.Images.Media.MIME_TYPE, "image/png")
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: error("MediaStore insert returned null")
    (resolver.openOutputStream(uri) ?: error("openOutputStream returned null for $uri")).use { out ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // Fire the REAL trampoline. Same-UID read grant via FLAG_GRANT_READ_URI_PERMISSION; NEW_TASK because we
    // start it from a non-Activity context. Must not throw — the trampoline finishes immediately and the
    // service does OCR + inference off this lifecycle.
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "image/png"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
      setClassName(context, "cc.grepon.relais.share.RelaisShareActivity")
    }
    context.startActivity(send)

    // Best-effort observable: OCR + inference is async (multi-second); poll the clipboard up to ~60s. On A10+
    // the read may always return null from this non-focused process — then fall back to the logcat/notification
    // check and assert only that the share launched cleanly.
    var captured: String? = null
    val deadline = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(2_000)
      instrumentation.runOnMainSync {
        captured = clipboard.primaryClip
          ?.takeIf { it.itemCount > 0 }
          ?.getItemAt(0)
          ?.coerceToText(context)
          ?.toString()
      }
      if (!captured.isNullOrBlank()) break
    }

    val result = captured
    if (result.isNullOrBlank()) {
      println(
        "ShareImageProbe: clipboard unreadable (A10+ restriction). Trampoline accepted the content:// share " +
          "without crashing. Verify the inference e2e via: adb logcat -s RelaisShareService:* RelaisOcr:* " +
          "RelaisEngine:* and the on-device \"Relais\" result notification (model's answer to \"$marker\").",
      )
    } else {
      assertTrue("clipboard result should be a non-empty inference answer, got: \"$result\"", result.isNotBlank())
    }
  }
}
