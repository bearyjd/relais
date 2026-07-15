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

package cc.grepon.relais.tts

import android.content.Context

/**
 * Registers the on-device TTS engine at node startup (issue #168). Unlike image-gen/OCR (GMS-bound,
 * `full`-only, stubbed on `degoogled`), the sherpa-onnx runtime has no Google dependency, so this lives
 * in `main` and registers on ALL flavors — de-Googled nodes get `/v1/audio/speech` too. Called from
 * [cc.grepon.relais.RelaisNodeService] alongside the embedder / image-gen registrations.
 */
object TtsRegistration {
  fun register(context: Context) {
    RelaisTtsEngineProvider.register(SherpaTtsEngine)
  }
}
