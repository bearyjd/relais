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

import cc.grepon.relais.rag.RagVectorCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Pure JVM tests for [RagVectorCodec] — the float32 BLOB encoding of RAG chunk embeddings. */
class RagVectorCodecTest {

  @Test fun `encode then decode round-trips exactly`() {
    val v = floatArrayOf(0f, 1f, -1f, 0.5f, -0.25f, 3.4028235e38f, 1.4e-45f)
    assertArrayEquals(v, RagVectorCodec.decode(RagVectorCodec.encode(v)), 0f)
  }

  @Test fun `encoded length is four bytes per dimension`() {
    assertEquals(256 * 4, RagVectorCodec.encode(FloatArray(256)).size)
  }

  @Test fun `empty vector round-trips to empty`() {
    assertEquals(0, RagVectorCodec.encode(FloatArray(0)).size)
    assertEquals(0, RagVectorCodec.decode(ByteArray(0)).size)
  }

  @Test fun `byte order is fixed little-endian`() {
    // 1.0f = 0x3F800000; little-endian bytes = 00 00 80 3F.
    val bytes = RagVectorCodec.encode(floatArrayOf(1.0f))
    assertArrayEquals(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F), bytes)
  }

  @Test fun `decode rejects a length that is not a multiple of four`() {
    try {
      RagVectorCodec.decode(ByteArray(7))
      fail("expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
  }
}
