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

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

// ---------------------------------------------------------------------------
// OpenAI /v1/images/generations helpers (Feature 16)
//
// Pure (no Context, no Android types) so the whole request-shaping + response-shaping surface is
// unit-testable on the JVM ([RelaisImagesEndpointTest]) — the HTTP route is a thin shell over these,
// exactly like the /v1/embeddings helpers in RelaisHttpServer.kt. Base64 uses java.util.Base64 (not
// android.util.Base64) so the envelope round-trips in plain JVM tests; the basic encoder emits no line
// breaks, matching android's NO_WRAP. minSdk 31 carries java.util.Base64 (API 26+).
// ---------------------------------------------------------------------------

/**
 * A parsed + bounded image-generation request, ready to drive the generator. [size] is validated
 * against the supported set but carried (not yet passed to the backend): MediaPipe SD 1.5 is
 * fixed-512x512 so the value is implied today, but the field is kept for a future multi-size backend.
 */
internal data class ImageGenRequest(
  val prompt: String,
  val n: Int,
  val size: String,
  val steps: Int,
  val seed: Long?,
)

/** Bounds applied to an image request: batch size, step range, and the accepted output sizes. */
internal data class ImageGenLimits(
  val maxImages: Int,
  val defaultSteps: Int,
  val minSteps: Int,
  val maxSteps: Int,
  val supportedSizes: Set<String>,
  val defaultSize: String,
) {
  init {
    // Self-validating config: the default-step path skips clamping, and the default size skips the
    // supported-set check, so a misconfigured default would silently bypass the bound it's meant to
    // enforce. Fail loud at construction instead.
    require(maxImages >= 1) { "maxImages must be >= 1" }
    require(minSteps in 1..maxSteps) { "minSteps must be in 1..maxSteps" }
    require(defaultSteps in minSteps..maxSteps) { "defaultSteps must be in minSteps..maxSteps" }
    require(supportedSizes.isNotEmpty()) { "supportedSizes must be non-empty" }
    require(defaultSize in supportedSizes) { "defaultSize must be one of supportedSizes" }
  }
}

/** Outcome of parsing + bounding an image request: a ready request, or a 400 message. */
internal sealed interface ImageRequestResult {
  data class Valid(val request: ImageGenRequest) : ImageRequestResult
  data class Invalid(val message: String) : ImageRequestResult
}

/**
 * Parses an OpenAI `/v1/images/generations` body and applies [limits], returning [ImageRequestResult].
 * Rules (the route maps [ImageRequestResult.Invalid] → 400):
 * - `prompt`: required, must be a non-blank string.
 * - `response_format`: only `b64_json` (default when absent). A LAN node serves no hosted URLs, so
 *   `url` is rejected rather than silently downgraded.
 * - `size`: must be one of [ImageGenLimits.supportedSizes] (default when absent). MediaPipe SD 1.5 is
 *   fixed-resolution, so unsupported sizes are a hard 400, not an upscale.
 * - `n`: integer in `[1, maxImages]`; out of range → 400 (mirrors how `/v1/embeddings` rejects an
 *   over-cap batch rather than silently clamping).
 * - `x_relais_steps`: integer; **clamped** to `[minSteps, maxSteps]` (a quality/time knob, so clamping
 *   is friendlier than rejecting). Default [ImageGenLimits.defaultSteps] when absent.
 * - `seed`: optional integer.
 *
 * Pure — no Context, no Android types.
 */
internal fun parseImageRequest(body: JSONObject, limits: ImageGenLimits): ImageRequestResult {
  // prompt: required non-blank string (optString would coerce a bare number/bool, so check the raw type)
  if (!body.has("prompt") || body.isNull("prompt")) return ImageRequestResult.Invalid("missing 'prompt'")
  val rawPrompt = body.get("prompt")
  if (rawPrompt !is String || rawPrompt.isBlank()) {
    return ImageRequestResult.Invalid("'prompt' must be a non-empty string")
  }

  // response_format: only b64_json (no hosted URLs on a LAN node)
  val format = body.optString("response_format").ifBlank { "b64_json" }
  if (format != "b64_json") {
    return ImageRequestResult.Invalid("unsupported 'response_format' '$format' (only 'b64_json' is served)")
  }

  // size: one of the supported set (fixed-resolution model — no upscaling)
  val size = body.optString("size").ifBlank { limits.defaultSize }
  if (size !in limits.supportedSizes) {
    return ImageRequestResult.Invalid(
      "unsupported 'size' '$size' (supported: ${limits.supportedSizes.sorted().joinToString(", ")})"
    )
  }

  // n: integer in [1, maxImages]
  val n: Int =
    if (body.has("n") && !body.isNull("n")) {
      val raw = body.get("n")
      if (raw !is Number) return ImageRequestResult.Invalid("'n' must be an integer")
      val value = raw.toInt()
      when {
        value < 1 -> return ImageRequestResult.Invalid("'n' must be >= 1")
        value > limits.maxImages ->
          return ImageRequestResult.Invalid("'n' exceeds the per-request limit (max ${limits.maxImages})")
        else -> value
      }
    } else 1

  // x_relais_steps: integer, clamped to [minSteps, maxSteps]
  val steps: Int =
    if (body.has("x_relais_steps") && !body.isNull("x_relais_steps")) {
      val raw = body.get("x_relais_steps")
      if (raw !is Number) return ImageRequestResult.Invalid("'x_relais_steps' must be an integer")
      raw.toInt().coerceIn(limits.minSteps, limits.maxSteps)
    } else limits.defaultSteps

  // seed: optional integer
  val seed: Long? =
    if (body.has("seed") && !body.isNull("seed")) {
      val raw = body.get("seed")
      if (raw !is Number) return ImageRequestResult.Invalid("'seed' must be an integer")
      raw.toLong()
    } else null

  return ImageRequestResult.Valid(ImageGenRequest(rawPrompt, n, size, steps, seed))
}

/**
 * Shapes the OpenAI-compatible `/v1/images/generations` success response from already-encoded [pngs].
 * Each data entry is `{ "b64_json": "<base64 PNG>" }` in generation order; [createdSec] is the Unix
 * epoch second the response was built. Mirrors the OpenAI images schema (`response_format=b64_json`).
 *
 * Pure — no Context, no Android types.
 */
internal fun buildImagesResponse(pngs: List<ByteArray>, createdSec: Long): JSONObject {
  val encoder = Base64.getEncoder() // basic encoder = no line breaks, == android NO_WRAP
  val data = JSONArray()
  pngs.forEach { png ->
    data.put(JSONObject().put("b64_json", encoder.encodeToString(png)))
  }
  return JSONObject()
    .put("created", createdSec)
    .put("data", data)
}

/**
 * The OpenAI error envelope `{error:{message,type}}` used by the images route for its 400/501 paths.
 * Pure — unit-testable on the JVM.
 */
internal fun buildImagesError(message: String, type: String): JSONObject =
  JSONObject().put("error", JSONObject().put("message", message).put("type", type))
