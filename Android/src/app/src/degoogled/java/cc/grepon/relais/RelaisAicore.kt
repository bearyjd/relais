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

package cc.grepon.relais

import android.content.Context

/**
 * De-Googled flavor stub for the AICore (Gemini Nano / ML Kit GenAI) NPU path. AICore requires
 * Google Play Services, which the `degoogled` flavor excludes, so the node never routes to it:
 * [available] always returns false, so [BackendSelector] never selects the NPU backend and
 * [generate] is unreachable. Inference runs on the bundled non-GMS litertlm runtime exactly as in
 * the `full` flavor. The real GMS-backed implementation lives in `src/full/`.
 */
object RelaisAicore {
  /** AICore is unavailable in the de-Googled flavor (no Play Services). */
  fun available(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

  /** Unreachable (guarded by [available]); fails loudly if a caller ever bypasses the gate. */
  fun generate(@Suppress("UNUSED_PARAMETER") request: RelaisRequest): String =
    error("AICore is not available in the de-Googled build")
}
