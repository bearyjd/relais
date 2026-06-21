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

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pure JVM tests for the `POST /v1/images/generations` shaping + validation helpers. The HTTP route is
 * a thin shell over these (mirroring how the [parseEmbeddingInputs]/[buildEmbeddingsResponse] helpers
 * back `/v1/embeddings`); testing the pure functions exercises every request/response path without a
 * socket. The 200 path requires a registered [cc.grepon.relais.imagegen.RelaisImageGenerator]; until
 * the MediaPipe backend lands none is registered, so the live endpoint stays an honest 501 (the
 * provider seam is covered by [RelaisImageGeneratorProviderTest]).
 */
class RelaisImagesEndpointTest {

  private val limits = ImageGenLimits(
    maxImages = 4,
    defaultSteps = 20,
    minSteps = 1,
    maxSteps = 50,
    supportedSizes = setOf("512x512"),
    defaultSize = "512x512",
  )

  private fun valid(result: ImageRequestResult): ImageGenRequest {
    assertTrue("expected Valid but was $result", result is ImageRequestResult.Valid)
    return (result as ImageRequestResult.Valid).request
  }

  private fun invalidMessage(result: ImageRequestResult): String {
    assertTrue("expected Invalid but was $result", result is ImageRequestResult.Invalid)
    return (result as ImageRequestResult.Invalid).message
  }

  // --- parseImageRequest: prompt ---

  @Test fun `minimal body applies all defaults`() {
    val req = valid(parseImageRequest(JSONObject().put("prompt", "a red bicycle"), limits))
    assertEquals("a red bicycle", req.prompt)
    assertEquals(1, req.n)
    assertEquals("512x512", req.size)
    assertEquals(20, req.steps)
    assertNull(req.seed)
  }

  @Test fun `missing prompt is invalid`() {
    assertTrue(invalidMessage(parseImageRequest(JSONObject().put("n", 1), limits)).contains("prompt"))
  }

  @Test fun `blank prompt is invalid`() {
    assertTrue(invalidMessage(parseImageRequest(JSONObject().put("prompt", "   "), limits)).contains("prompt"))
  }

  @Test fun `non-string prompt is invalid`() {
    // optString would coerce 42 -> "42"; the raw-type check rejects it like OpenAI does.
    assertTrue(invalidMessage(parseImageRequest(JSONObject(/* language=JSON */ """{"prompt":42}"""), limits)).contains("prompt"))
  }

  // --- parseImageRequest: response_format ---

  @Test fun `explicit b64_json is accepted`() {
    val req = valid(parseImageRequest(JSONObject().put("prompt", "x").put("response_format", "b64_json"), limits))
    assertEquals("x", req.prompt)
  }

  @Test fun `response_format url is rejected`() {
    val msg = invalidMessage(parseImageRequest(JSONObject().put("prompt", "x").put("response_format", "url"), limits))
    assertTrue(msg.contains("response_format"))
  }

  // --- parseImageRequest: size ---

  @Test fun `unsupported size is rejected`() {
    val msg = invalidMessage(parseImageRequest(JSONObject().put("prompt", "x").put("size", "1024x1024"), limits))
    assertTrue(msg.contains("size"))
    assertTrue("error lists the supported size", msg.contains("512x512"))
  }

  @Test fun `supported size is accepted`() {
    val req = valid(parseImageRequest(JSONObject().put("prompt", "x").put("size", "512x512"), limits))
    assertEquals("512x512", req.size)
  }

  // --- parseImageRequest: n ---

  @Test fun `n within bounds is honored`() {
    assertEquals(3, valid(parseImageRequest(JSONObject().put("prompt", "x").put("n", 3), limits)).n)
  }

  @Test fun `n at the cap is honored`() {
    assertEquals(4, valid(parseImageRequest(JSONObject().put("prompt", "x").put("n", 4), limits)).n)
  }

  @Test fun `n above the cap is rejected (not silently clamped)`() {
    val msg = invalidMessage(parseImageRequest(JSONObject().put("prompt", "x").put("n", 5), limits))
    assertTrue(msg.contains("n"))
  }

  @Test fun `n below one is rejected`() {
    assertTrue(invalidMessage(parseImageRequest(JSONObject().put("prompt", "x").put("n", 0), limits)).contains("n"))
  }

  @Test fun `non-integer n is rejected`() {
    assertTrue(invalidMessage(parseImageRequest(JSONObject(/* language=JSON */ """{"prompt":"x","n":"two"}"""), limits)).contains("n"))
  }

  // --- parseImageRequest: x_relais_steps (clamped, not rejected) ---

  @Test fun `steps above max are clamped`() {
    assertEquals(50, valid(parseImageRequest(JSONObject().put("prompt", "x").put("x_relais_steps", 100), limits)).steps)
  }

  @Test fun `steps below min are clamped`() {
    assertEquals(1, valid(parseImageRequest(JSONObject().put("prompt", "x").put("x_relais_steps", 0), limits)).steps)
  }

  @Test fun `steps within range are honored`() {
    assertEquals(30, valid(parseImageRequest(JSONObject().put("prompt", "x").put("x_relais_steps", 30), limits)).steps)
  }

  @Test fun `non-integer steps are rejected`() {
    assertTrue(invalidMessage(parseImageRequest(JSONObject(/* language=JSON */ """{"prompt":"x","x_relais_steps":"hi"}"""), limits)).contains("x_relais_steps"))
  }

  // --- parseImageRequest: seed ---

  @Test fun `seed is parsed when present`() {
    assertEquals(42L, valid(parseImageRequest(JSONObject().put("prompt", "x").put("seed", 42), limits)).seed)
  }

  @Test fun `non-integer seed is rejected`() {
    assertTrue(invalidMessage(parseImageRequest(JSONObject(/* language=JSON */ """{"prompt":"x","seed":"abc"}"""), limits)).contains("seed"))
  }

  // --- buildImagesResponse ---

  @Test fun `response shapes N entries with created and b64_json`() {
    val pngs = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6, 7))
    val resp = buildImagesResponse(pngs, createdSec = 1701339600L)

    assertEquals(1701339600L, resp.getLong("created"))
    val data = resp.getJSONArray("data")
    assertEquals(2, data.length())
    for (i in 0 until data.length()) {
      assertTrue(data.getJSONObject(i).has("b64_json"))
    }
  }

  @Test fun `response base64 round-trips the PNG bytes (NO_WRAP)`() {
    val png = ByteArray(200) { (it % 256).toByte() } // long enough that a wrapping encoder would insert breaks
    val resp = buildImagesResponse(listOf(png), createdSec = 0L)
    val b64 = resp.getJSONArray("data").getJSONObject(0).getString("b64_json")
    assertTrue("base64 must not contain line breaks", !b64.contains("\n") && !b64.contains("\r"))
    assertArrayEquals(png, Base64.getDecoder().decode(b64))
  }

  // --- buildImagesError ---

  @Test fun `error envelope uses the OpenAI error shape`() {
    val err = buildImagesError("image generation model not provisioned", "not_implemented")
    val error = err.getJSONObject("error")
    assertEquals("image generation model not provisioned", error.getString("message"))
    assertEquals("not_implemented", error.getString("type"))
  }
}
