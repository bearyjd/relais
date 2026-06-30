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

import cc.grepon.relais.imagegen.DEFAULT_IMAGE_MODEL_ID
import cc.grepon.relais.imagegen.IMAGE_MODELS
import cc.grepon.relais.imagegen.IMAGE_MODEL_SD15
import cc.grepon.relais.imagegen.IMAGE_MODEL_TURBO
import cc.grepon.relais.imagegen.imageModelById
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the image-model registry (image-gen #16 PR-B). */
class ImageModelTest {

  @Test fun `turbo is the default and resolves`() {
    assertEquals("turbo", DEFAULT_IMAGE_MODEL_ID)
    assertEquals(IMAGE_MODEL_TURBO, imageModelById("turbo"))
  }

  @Test fun `sd15 resolves`() {
    assertEquals(IMAGE_MODEL_SD15, imageModelById("sd15"))
  }

  @Test fun `unknown id and null are unresolved`() {
    assertNull(imageModelById("nope"))
    assertNull(imageModelById(null))
  }

  @Test fun `custom requires a url`() {
    assertNull(imageModelById("custom"))
    val m = imageModelById("custom", customUrl = "https://h.example/models/my.gguf", customSha = "deadbeef")
    assertNotNull(m)
    requireNotNull(m)
    assertEquals("custom", m.id)
    assertEquals("my.gguf", m.fileName)
    assertEquals("https://h.example/models/my.gguf", m.url)
    assertEquals("deadbeef", m.sha256)
    assertEquals(-1L, m.sizeBytes)
  }

  @Test fun `custom filename strips a query string`() {
    val m = requireNotNull(imageModelById("custom", customUrl = "https://h.example/a/b.gguf?download=true"))
    assertEquals("b.gguf", m.fileName)
  }

  @Test fun `turbo url targets the self-host resolve path`() {
    assertEquals(
      "https://huggingface.co/dispense1301/relais-imagegen/resolve/main/sdturbo.gguf",
      IMAGE_MODEL_TURBO.url,
    )
  }

  @Test fun `model sampler params match the plan`() {
    assertEquals(4, IMAGE_MODEL_TURBO.steps)
    assertEquals(1f, IMAGE_MODEL_TURBO.cfg, 0f)
    assertEquals(20, IMAGE_MODEL_SD15.steps)
    assertEquals(7f, IMAGE_MODEL_SD15.cfg, 0f)
  }

  @Test fun `every built-in model is SHA-pinned with a positive size`() {
    for (m in IMAGE_MODELS) {
      assertEquals("64-hex SHA-256 for ${m.id}", 64, m.sha256.length)
      assertTrue("positive size for ${m.id}", m.sizeBytes > 0)
    }
  }
}
