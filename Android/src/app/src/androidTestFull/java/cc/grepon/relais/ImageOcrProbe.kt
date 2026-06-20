/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the terms of the GNU Affero
 * General Public License as published by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General
 * Public License for more details.
 */

package cc.grepon.relais

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for Feature #13 OCR. Validates that the BUNDLED ML Kit Latin recognizer actually runs
 * on the device by rendering known text to a bitmap and OCR'ing it — no content-URI/share plumbing
 * needed, so it isolates the recognition capability itself.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.ImageOcrProbe -e RELAIS_PROBE 1 \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ImageOcrProbe {

  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun recognizesRenderedText() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")

    val bitmap = Bitmap.createBitmap(720, 240, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
      drawColor(Color.WHITE)
      drawText(
        "RELAIS OCR",
        48f,
        150f,
        Paint().apply {
          color = Color.BLACK
          textSize = 96f
          isAntiAlias = true
        },
      )
    }

    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val text = try {
      Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
    } finally {
      recognizer.close()
    }

    assertTrue("expected the recognizer to read 'RELAIS', got: \"$text\"", text.uppercase().contains("RELAIS"))
  }
}
