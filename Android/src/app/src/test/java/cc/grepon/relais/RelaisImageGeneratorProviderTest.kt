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
import cc.grepon.relais.imagegen.RelaisImageGenerator
import cc.grepon.relais.imagegen.RelaisImageGeneratorProvider
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure JVM test for the image-generator registration seam. The IMPL is delivered by the MediaPipe
 * follow-up to feature #16; until something registers one, `get()` is null — the single source of
 * truth `POST /v1/images/generations` reads to return an honest 501. No Android Context instance
 * needed (the interface methods are never invoked here).
 */
class RelaisImageGeneratorProviderTest {

  private val fake = object : RelaisImageGenerator {
    override fun isAvailable(context: Context): Boolean = true
    override fun generate(
      context: Context,
      prompt: String,
      steps: Int,
      seed: Long?,
      shouldCancel: () -> Boolean,
    ): ByteArray = ByteArray(0)
  }

  @After fun reset() = RelaisImageGeneratorProvider.register(null)

  @Test fun `get is null until a generator is registered`() {
    RelaisImageGeneratorProvider.register(null)
    assertNull(RelaisImageGeneratorProvider.get())
  }

  @Test fun `get returns the registered generator`() {
    RelaisImageGeneratorProvider.register(fake)
    assertSame(fake, RelaisImageGeneratorProvider.get())
  }

  @Test fun `registering null clears a prior generator`() {
    RelaisImageGeneratorProvider.register(fake)
    RelaisImageGeneratorProvider.register(null)
    assertNull(RelaisImageGeneratorProvider.get())
  }
}
