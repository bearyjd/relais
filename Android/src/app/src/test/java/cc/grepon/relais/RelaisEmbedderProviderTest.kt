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
import cc.grepon.relais.embed.RelaisEmbedder
import cc.grepon.relais.embed.RelaisEmbedderProvider
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure JVM test for the embedding-model registration seam. The IMPL is delivered by feature #6; until
 * something registers one, `get()` is null — the single source of truth `/v1/embeddings` (→501) and
 * RAG (→disabled) read. No Android Context instance needed (the interface methods are never invoked).
 */
class RelaisEmbedderProviderTest {

  private val fake = object : RelaisEmbedder {
    override val dim: Int = 3
    override fun isAvailable(context: Context): Boolean = true
    override fun embed(context: Context, texts: List<String>): List<FloatArray> = texts.map { FloatArray(dim) }
    override fun countTokens(texts: List<String>): Int = texts.size
  }

  @After fun reset() = RelaisEmbedderProvider.register(null)

  @Test fun `get is null until an embedder is registered`() {
    RelaisEmbedderProvider.register(null)
    assertNull(RelaisEmbedderProvider.get())
  }

  @Test fun `get returns the registered embedder`() {
    RelaisEmbedderProvider.register(fake)
    assertSame(fake, RelaisEmbedderProvider.get())
  }

  @Test fun `registering null clears a prior embedder`() {
    RelaisEmbedderProvider.register(fake)
    RelaisEmbedderProvider.register(null)
    assertNull(RelaisEmbedderProvider.get())
  }
}
