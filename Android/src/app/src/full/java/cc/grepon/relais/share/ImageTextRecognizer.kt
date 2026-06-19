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

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import cc.grepon.relais.common.decodeSampledBitmapFromUri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device Latin OCR for share-in images (#13). Wraps the **bundled** ML Kit Latin recognizer
 * (`com.google.mlkit:text-recognition` — model in-APK; note it transitively pulls the Play-Services
 * text-recognition pipeline, so this path is not GMS-free and the recognizer can fail to initialise on a
 * device without Play Services — handled gracefully below). All recognition runs on-device; no image
 * leaves the phone.
 *
 * Each image is **downsampled before decode** ([MAX_OCR_DIM]) — a shared photo can be 50+ MP, which as a
 * full ARGB_8888 bitmap (~200 MB) could OOM a process already hosting a multi-GB LLM; downsampling bounds
 * memory and matches ML Kit's recommended input size. EXIF orientation is applied via the rotation passed
 * to [InputImage.fromBitmap]. The image count is capped ([MAX_IMAGES]) so a hostile `SEND_MULTIPLE` can't
 * pin the share slot with unbounded OCR work. A failed/unreadable image is dropped (logged), not fatal.
 */
object ImageTextRecognizer {

  private const val TAG = "RelaisOcr"
  private const val MAX_OCR_DIM = 2048 // downsample cap — bounds memory + ML Kit's recommended input size
  private const val MAX_IMAGES = 8 // cap OCR work per share (an attacker controls SEND_MULTIPLE length)

  /** OCRs up to [MAX_IMAGES] of [uris], returning recognized text per image (blank / failed dropped). */
  suspend fun recognize(context: Context, uris: List<Uri>): List<String> {
    if (uris.isEmpty()) return emptyList()
    val recognizer =
      try {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        // No Play Services (de-Googled device) → recognizer can't initialise; degrade to "no OCR".
        Log.w(TAG, "OCR unavailable (recognizer init failed): ${e.message}")
        return emptyList()
      }
    return try {
      uris.take(MAX_IMAGES).mapNotNull { recognizeOne(context, recognizer, it) }
    } finally {
      runCatching { recognizer.close() } // release the native recognizer
    }
  }

  private suspend fun recognizeOne(context: Context, recognizer: TextRecognizer, uri: Uri): String? {
    // Downsample on decode so a huge image can't OOM the node. Throwable (incl. OutOfMemoryError on a
    // pathological image) drops this image rather than killing the resident LLM process.
    val bitmap =
      try {
        decodeSampledBitmapFromUri(context, uri, MAX_OCR_DIM, MAX_OCR_DIM)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        Log.w(TAG, "could not decode a shared image: ${e.message}")
        null
      } ?: return null
    return try {
      val image = InputImage.fromBitmap(bitmap, rotationDegrees(exifOrientation(context, uri)))
      recognizer.process(image).await().text.trim().takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Log.w(TAG, "OCR failed for a shared image: ${e.message}")
      null
    } finally {
      bitmap.recycle() // recognition is complete (we awaited it) — free the bitmap on every path
    }
  }

  /** EXIF orientation of [uri], or [ExifInterface.ORIENTATION_NORMAL] if unreadable. */
  private fun exifOrientation(context: Context, uri: Uri): Int =
    runCatching {
      context.contentResolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

  /** Maps an EXIF orientation to the rotation degrees ML Kit's [InputImage.fromBitmap] expects. */
  private fun rotationDegrees(orientation: Int): Int =
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> 90
      ExifInterface.ORIENTATION_ROTATE_180 -> 180
      ExifInterface.ORIENTATION_ROTATE_270 -> 270
      else -> 0 // normal + the rare flip orientations (ML Kit rotation can't express a flip)
    }
}

/** Bridges a GMS [Task] to a coroutine without pulling kotlinx-coroutines-play-services. */
private suspend fun <T> Task<T>.await(): T =
  suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
  }
