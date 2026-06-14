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

import cc.grepon.relais.templates.PromptTemplate
import cc.grepon.relais.templates.PromptTemplateStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Robolectric (file IO) tests for the canonical, JSON-backed prompt-template store. */
@RunWith(RobolectricTestRunner::class)
class PromptTemplateStoreTest {

  private val ctx get() = RuntimeEnvironment.getApplication()

  @Before fun setUp() {
    File(ctx.filesDir, "relais_templates.json").delete()
    PromptTemplateStore.resetCacheForTest()
  }

  @After fun tearDown() {
    File(ctx.filesDir, "relais_templates.json").delete()
    PromptTemplateStore.resetCacheForTest()
  }

  @Test fun `seeds built-ins on first run`() {
    val ids = PromptTemplateStore.all(ctx).map { it.id }.toSet()
    assertTrue(ids.containsAll(setOf("default", "terse-coder", "json-only", "translator-fr")))
  }

  @Test fun `resolve returns a built-in`() {
    assertNotNull(PromptTemplateStore.resolve(ctx, "terse-coder"))
  }

  @Test fun `isUnknown true only for a non-blank unmatched id`() {
    assertFalse(PromptTemplateStore.isUnknown(ctx, null))
    assertFalse(PromptTemplateStore.isUnknown(ctx, ""))
    assertFalse(PromptTemplateStore.isUnknown(ctx, "terse-coder"))
    assertTrue(PromptTemplateStore.isUnknown(ctx, "does-not-exist"))
  }

  @Test fun `upsert adds a custom template and persists across cache reset`() {
    assertTrue(PromptTemplateStore.upsert(ctx, PromptTemplate("mine", "Mine", "be terse")))
    assertEquals("be terse", PromptTemplateStore.resolve(ctx, "mine")?.system)
    PromptTemplateStore.resetCacheForTest()
    assertEquals("be terse", PromptTemplateStore.resolve(ctx, "mine")?.system)
  }

  @Test fun `upsert rejects an id colliding with a built-in`() {
    assertFalse(PromptTemplateStore.upsert(ctx, PromptTemplate("default", "Hijack", "evil")))
    assertEquals("", PromptTemplateStore.resolve(ctx, "default")?.system) // unchanged built-in
  }

  @Test fun `delete removes a custom template but not a built-in`() {
    PromptTemplateStore.upsert(ctx, PromptTemplate("mine", "Mine", "x"))
    assertTrue(PromptTemplateStore.delete(ctx, "mine"))
    assertNull(PromptTemplateStore.resolve(ctx, "mine"))
    assertFalse(PromptTemplateStore.delete(ctx, "terse-coder"))
    assertNotNull(PromptTemplateStore.resolve(ctx, "terse-coder"))
  }

  @Test fun `a corrupt file reseeds built-ins instead of bricking`() {
    File(ctx.filesDir, "relais_templates.json").writeText("{ this is not valid json ]")
    PromptTemplateStore.resetCacheForTest()
    assertNotNull(PromptTemplateStore.resolve(ctx, "terse-coder"))
  }

  @Test fun `system text is bounded to guard prompt-size DoS`() {
    PromptTemplateStore.upsert(ctx, PromptTemplate("big", "Big", "x".repeat(20_000)))
    val len = PromptTemplateStore.resolve(ctx, "big")?.system?.length ?: 0
    assertTrue("system should be capped, was $len", len in 1..8_192)
  }
}
