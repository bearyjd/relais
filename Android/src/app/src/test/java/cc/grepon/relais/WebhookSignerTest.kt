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

import cc.grepon.relais.batch.WebhookSigner
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM tests for [WebhookSigner] — HMAC-SHA256 webhook signatures, pinned to a known vector. */
class WebhookSignerTest {

  @Test fun `matches the canonical HMAC-SHA256 test vector`() {
    // RFC-style vector: HMAC_SHA256("key", "The quick brown fox jumps over the lazy dog").
    assertEquals(
      "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
      WebhookSigner.sign("The quick brown fox jumps over the lazy dog", "key"),
    )
  }

  @Test fun `header is prefixed sha256`() {
    val h = WebhookSigner.header("""{"job_id":"x"}""", "secret")
    assertEquals("sha256=" + WebhookSigner.sign("""{"job_id":"x"}""", "secret"), h)
    assertEquals(64 + "sha256=".length, h.length) // 32-byte digest = 64 hex chars
  }

  @Test fun `is deterministic and secret-sensitive`() {
    val p = """{"a":1}"""
    assertEquals(WebhookSigner.sign(p, "s1"), WebhookSigner.sign(p, "s1"))
    assert(WebhookSigner.sign(p, "s1") != WebhookSigner.sign(p, "s2"))
  }
}
