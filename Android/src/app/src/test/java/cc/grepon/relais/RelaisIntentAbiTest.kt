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

import cc.grepon.relais.automation.RelaisIntentAbi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the Tasker/Automate intent ABI (#8). [RelaisIntentAbi.parseRequest] is pure
 * (lambda-driven, no Android types), so its cases run as plain JVM logic; [buildResultIntent] returns
 * a real [android.content.Intent], so the result-extra assertions (and the critical "token never
 * leaks into the result" guard) run under Robolectric, which gives a faithful Intent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelaisIntentAbiTest {

  /** Builds a `getString` lambda from a map; missing keys return null (mirrors Intent.getStringExtra). */
  private fun strings(map: Map<String, String?>): (String) -> String? = { map[it] }

  /** Builds a `getLong` lambda from a map; missing keys return the supplied default. */
  private fun longs(map: Map<String, Long>): (String, Long) -> Long = { k, d -> map[k] ?: d }

  // ---- parseRequest: required fields ----

  @Test fun `null when prompt missing`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_TOKEN to "k")),
      longs(emptyMap()),
    )
    assertNull(req)
  }

  @Test fun `null when prompt blank`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "   ", RelaisIntentAbi.EXTRA_TOKEN to "k")),
      longs(emptyMap()),
    )
    assertNull(req)
  }

  @Test fun `null when token missing`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "hi")),
      longs(emptyMap()),
    )
    assertNull(req)
  }

  @Test fun `null when token blank`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "hi", RelaisIntentAbi.EXTRA_TOKEN to "  ")),
      longs(emptyMap()),
    )
    assertNull(req)
  }

  @Test fun `minimal valid request parses with defaults`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "summarize this", RelaisIntentAbi.EXTRA_TOKEN to "secret")),
      longs(emptyMap()),
    )
    requireNotNull(req)
    assertEquals("summarize this", req.prompt)
    assertEquals("secret", req.token)
    assertNull(req.system)
    assertNull(req.templateId)
    assertNull(req.resultPackage)
    assertNull(req.requestId)
    assertEquals(RelaisIntentAbi.DEFAULT_TIMEOUT_MS, req.timeoutMs)
  }

  // ---- parseRequest: prompt/token are trimmed of surrounding whitespace ----

  @Test fun `prompt and token are trimmed`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "  hi  ", RelaisIntentAbi.EXTRA_TOKEN to "  k  ")),
      longs(emptyMap()),
    )
    requireNotNull(req)
    assertEquals("hi", req.prompt)
    assertEquals("k", req.token)
  }

  // ---- parseRequest: optional fields ----

  @Test fun `optional fields parse when present`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(
        mapOf(
          RelaisIntentAbi.EXTRA_PROMPT to "p",
          RelaisIntentAbi.EXTRA_TOKEN to "k",
          RelaisIntentAbi.EXTRA_SYSTEM to "be terse",
          RelaisIntentAbi.EXTRA_TEMPLATE_ID to "triage",
          RelaisIntentAbi.EXTRA_RESULT_PACKAGE to "net.dinglisch.android.taskerm",
          RelaisIntentAbi.EXTRA_REQUEST_ID to "req-7",
        )
      ),
      longs(emptyMap()),
    )
    requireNotNull(req)
    assertEquals("be terse", req.system)
    assertEquals("triage", req.templateId)
    assertEquals("net.dinglisch.android.taskerm", req.resultPackage)
    assertEquals("req-7", req.requestId)
  }

  @Test fun `blank optional fields decode to null`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(
        mapOf(
          RelaisIntentAbi.EXTRA_PROMPT to "p",
          RelaisIntentAbi.EXTRA_TOKEN to "k",
          RelaisIntentAbi.EXTRA_SYSTEM to "   ",
          RelaisIntentAbi.EXTRA_TEMPLATE_ID to "",
          RelaisIntentAbi.EXTRA_RESULT_PACKAGE to "  ",
          RelaisIntentAbi.EXTRA_REQUEST_ID to "",
        )
      ),
      longs(emptyMap()),
    )
    requireNotNull(req)
    assertNull(req.system)
    assertNull(req.templateId)
    assertNull(req.resultPackage)
    assertNull(req.requestId)
  }

  // ---- parseRequest: timeout clamp (long-extra path) ----

  @Test fun `timeout below floor clamps up to min`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "p", RelaisIntentAbi.EXTRA_TOKEN to "k")),
      longs(mapOf(RelaisIntentAbi.EXTRA_TIMEOUT_MS to 5L)),
    )
    requireNotNull(req)
    assertEquals(RelaisIntentAbi.MIN_TIMEOUT_MS, req.timeoutMs)
  }

  @Test fun `timeout above ceiling clamps down to max`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "p", RelaisIntentAbi.EXTRA_TOKEN to "k")),
      longs(mapOf(RelaisIntentAbi.EXTRA_TIMEOUT_MS to 999_999L)),
    )
    requireNotNull(req)
    assertEquals(RelaisIntentAbi.MAX_TIMEOUT_MS, req.timeoutMs)
  }

  @Test fun `in-band timeout passes through`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "p", RelaisIntentAbi.EXTRA_TOKEN to "k")),
      longs(mapOf(RelaisIntentAbi.EXTRA_TIMEOUT_MS to 5_000L)),
    )
    requireNotNull(req)
    assertEquals(5_000L, req.timeoutMs)
  }

  // ---- parseRequest: timeout as String (Tasker can't set a long) ----

  @Test fun `string timeout is parsed and clamped`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "p", RelaisIntentAbi.EXTRA_TOKEN to "k", RelaisIntentAbi.EXTRA_TIMEOUT_MS to "5000")),
      // getLong returns the default here so the String path is exercised.
      longs(emptyMap()),
    )
    requireNotNull(req)
    assertEquals(5_000L, req.timeoutMs)
  }

  @Test fun `string timeout below floor clamps up`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "p", RelaisIntentAbi.EXTRA_TOKEN to "k", RelaisIntentAbi.EXTRA_TIMEOUT_MS to "1")),
      longs(emptyMap()),
    )
    requireNotNull(req)
    assertEquals(RelaisIntentAbi.MIN_TIMEOUT_MS, req.timeoutMs)
  }

  @Test fun `garbage string timeout falls back to default`() {
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "p", RelaisIntentAbi.EXTRA_TOKEN to "k", RelaisIntentAbi.EXTRA_TIMEOUT_MS to "not-a-number")),
      longs(emptyMap()),
    )
    requireNotNull(req)
    assertEquals(RelaisIntentAbi.DEFAULT_TIMEOUT_MS, req.timeoutMs)
  }

  @Test fun `long timeout extra wins over string timeout extra`() {
    // A real long extra is authoritative; the String fallback only fires when getLong returns default.
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "p", RelaisIntentAbi.EXTRA_TOKEN to "k", RelaisIntentAbi.EXTRA_TIMEOUT_MS to "120000")),
      longs(mapOf(RelaisIntentAbi.EXTRA_TIMEOUT_MS to 4_000L)),
    )
    requireNotNull(req)
    assertEquals(4_000L, req.timeoutMs)
  }

  // ---- parseRequest: prompt/system length caps ----

  @Test fun `over-cap prompt is truncated`() {
    val big = "x".repeat(RelaisIntentAbi.MAX_PROMPT_CHARS + 5_000)
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to big, RelaisIntentAbi.EXTRA_TOKEN to "k")),
      longs(emptyMap()),
    )
    requireNotNull(req)
    assertEquals(RelaisIntentAbi.MAX_PROMPT_CHARS, req.prompt.length)
  }

  @Test fun `over-cap system is truncated`() {
    val big = "y".repeat(RelaisIntentAbi.MAX_SYSTEM_CHARS + 2_000)
    val req = RelaisIntentAbi.parseRequest(
      strings(mapOf(RelaisIntentAbi.EXTRA_PROMPT to "p", RelaisIntentAbi.EXTRA_TOKEN to "k", RelaisIntentAbi.EXTRA_SYSTEM to big)),
      longs(emptyMap()),
    )
    requireNotNull(req)
    assertEquals(RelaisIntentAbi.MAX_SYSTEM_CHARS, req.system?.length)
  }

  // ---- buildResultIntent (real Intent under Robolectric) ----

  @Test fun `result ok intent carries response and request id and action`() {
    val intent = RelaisIntentAbi.buildResultIntent(
      action = RelaisIntentAbi.ACTION_INFER_RESULT,
      ok = true,
      response = "the answer",
      error = null,
      requestId = "req-9",
    )
    assertEquals(RelaisIntentAbi.ACTION_INFER_RESULT, intent.action)
    assertTrue(intent.getBooleanExtra(RelaisIntentAbi.EXTRA_OK, false))
    assertEquals("the answer", intent.getStringExtra(RelaisIntentAbi.EXTRA_RESPONSE))
    assertEquals("req-9", intent.getStringExtra(RelaisIntentAbi.EXTRA_REQUEST_ID))
    assertNull(intent.getStringExtra(RelaisIntentAbi.EXTRA_ERROR))
  }

  @Test fun `result error intent carries error and ok false`() {
    val intent = RelaisIntentAbi.buildResultIntent(
      action = RelaisIntentAbi.ACTION_INFER_RESULT,
      ok = false,
      response = null,
      error = "thermal_backpressure",
      requestId = null,
    )
    assertFalse(intent.getBooleanExtra(RelaisIntentAbi.EXTRA_OK, true))
    assertEquals("thermal_backpressure", intent.getStringExtra(RelaisIntentAbi.EXTRA_ERROR))
    assertNull(intent.getStringExtra(RelaisIntentAbi.EXTRA_RESPONSE))
  }

  @Test fun `request id extra is absent when null — not an empty string`() {
    val intent = RelaisIntentAbi.buildResultIntent(
      action = RelaisIntentAbi.ACTION_INFER_RESULT,
      ok = true,
      response = "x",
      error = null,
      requestId = null,
    )
    assertFalse(intent.hasExtra(RelaisIntentAbi.EXTRA_REQUEST_ID))
  }

  /**
   * CRITICAL: the RESULT intent must NEVER carry the caller's token (or any secret). A leaked token in
   * a broadcast/activity-result extra would hand the node's bearer key to whichever app captured it.
   */
  @Test fun `result intent never carries the token`() {
    val secret = "deadbeefcafef00d"
    val intent = RelaisIntentAbi.buildResultIntent(
      action = RelaisIntentAbi.ACTION_INFER_RESULT,
      ok = true,
      response = secret, // even when the secret string is the legitimate response payload…
      error = null,
      requestId = "r",
    )
    // …it must not appear under the token extra key, and no token extra may exist at all.
    assertFalse(intent.hasExtra(RelaisIntentAbi.EXTRA_TOKEN))
    assertNull(intent.getStringExtra(RelaisIntentAbi.EXTRA_TOKEN))
    val extras = intent.extras
    if (extras != null) {
      for (key in extras.keySet()) {
        assertFalse("token extra key leaked: $key", key == RelaisIntentAbi.EXTRA_TOKEN)
      }
    }
  }
}
