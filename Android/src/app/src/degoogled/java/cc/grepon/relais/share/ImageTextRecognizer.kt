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

/**
 * De-Googled flavor stub for share-in image OCR (#13). The ML Kit Latin recognizer transitively
 * pulls the Play-Services text-recognition pipeline, which the `degoogled` flavor excludes, so OCR
 * is unavailable. [recognize] returns no recognized text — the same graceful-degrade contract the
 * `full` impl already honors on a device without Play Services. A shared image simply yields no text;
 * a caption (`EXTRA_TEXT`) still flows through `RelaisShareService`. The real impl lives in `src/full/`.
 */
object ImageTextRecognizer {
  /** OCR is unavailable in the de-Googled build — returns no recognized text. */
  suspend fun recognize(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") uris: List<Uri>,
  ): List<String> = emptyList()
}
