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

package cc.grepon.relais.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes a RAG chunk embedding to/from the compact `BLOB` stored in Room. Little-endian float32,
 * 4 bytes per dimension — a 256-dim MRL vector is 1 KB. The on-disk byte order is fixed (LE) so a
 * vector written on one device decodes identically anywhere.
 */
object RagVectorCodec {

  private const val BYTES_PER_FLOAT = 4

  /** Packs [vector] into a little-endian float32 byte array (length `vector.size * 4`). */
  fun encode(vector: FloatArray): ByteArray {
    val buf = ByteBuffer.allocate(vector.size * BYTES_PER_FLOAT).order(ByteOrder.LITTLE_ENDIAN)
    for (v in vector) buf.putFloat(v)
    return buf.array()
  }

  /**
   * Unpacks a little-endian float32 byte array back into a [FloatArray].
   *
   * @throws IllegalArgumentException if [bytes] length is not a multiple of 4.
   */
  fun decode(bytes: ByteArray): FloatArray {
    require(bytes.size % BYTES_PER_FLOAT == 0) {
      "embedding blob length ${bytes.size} is not a multiple of $BYTES_PER_FLOAT"
    }
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / BYTES_PER_FLOAT) { buf.float }
  }
}
