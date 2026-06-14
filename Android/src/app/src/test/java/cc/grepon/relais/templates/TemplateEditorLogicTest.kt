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

package cc.grepon.relais.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the editor's decision logic. No Android types, so no Robolectric — the store's
 * own file-IO CRUD is covered separately in [cc.grepon.relais.PromptTemplateStoreTest].
 */
class TemplateEditorLogicTest {

  // --- validateTemplateForm ---------------------------------------------------------------------

  @Test fun `valid name and system is Ok`() {
    assertEquals(
      TemplateFormValidation.Ok,
      validateTemplateForm("My Template", "be terse", isNew = true, currentCustomCount = 0),
    )
  }

  @Test fun `blank name is NameBlank`() {
    assertEquals(
      TemplateFormValidation.NameBlank,
      validateTemplateForm("   ", "x", isNew = true, currentCustomCount = 0),
    )
  }

  @Test fun `name over the cap is NameTooLong`() {
    val tooLong = "n".repeat(PromptTemplateStore.MAX_NAME + 1) // 81
    assertEquals(
      TemplateFormValidation.NameTooLong,
      validateTemplateForm(tooLong, "x", isNew = true, currentCustomCount = 0),
    )
  }

  @Test fun `name exactly at the cap is Ok`() {
    val atCap = "n".repeat(PromptTemplateStore.MAX_NAME) // 80
    assertEquals(
      TemplateFormValidation.Ok,
      validateTemplateForm(atCap, "x", isNew = true, currentCustomCount = 0),
    )
  }

  @Test fun `system over the cap is SystemTooLong`() {
    val tooLong = "s".repeat(PromptTemplateStore.MAX_SYSTEM + 1) // 8193
    assertEquals(
      TemplateFormValidation.SystemTooLong,
      validateTemplateForm("Name", tooLong, isNew = true, currentCustomCount = 0),
    )
  }

  @Test fun `empty system is allowed (default built-in has none)`() {
    assertEquals(
      TemplateFormValidation.Ok,
      validateTemplateForm("Name", "", isNew = true, currentCustomCount = 0),
    )
  }

  @Test fun `new template at the custom cap is AtCapacity`() {
    assertEquals(
      TemplateFormValidation.AtCapacity,
      validateTemplateForm("Name", "x", isNew = true, currentCustomCount = PromptTemplateStore.CUSTOM_CAP),
    )
  }

  @Test fun `editing an existing template at the custom cap is Ok`() {
    // Editing replaces a row in place — it doesn't grow the count, so the cap must not block it.
    assertEquals(
      TemplateFormValidation.Ok,
      validateTemplateForm("Name", "x", isNew = false, currentCustomCount = PromptTemplateStore.CUSTOM_CAP),
    )
  }

  // --- slugifyTemplateId ------------------------------------------------------------------------

  @Test fun `slug lowercases and dashes spaces`() {
    assertEquals("my-cool-template", slugifyTemplateId("My Cool Template", emptySet()))
  }

  @Test fun `slug collapses runs and trims edge dashes`() {
    assertEquals("a-b", slugifyTemplateId("  --A   &&  B!! --  ", emptySet()))
  }

  @Test fun `slug reduces emoji and non-ascii letters to separators`() {
    // Only ASCII alphanumerics survive — a predictable id grammar for a headless node. Accented
    // letters and emoji become dashes (then collapse), so "héllo 🌍 wörld" → "h-llo-w-rld".
    assertEquals("h-llo-w-rld", slugifyTemplateId("héllo 🌍 wörld", emptySet()))
    // Pure ASCII passes through cleanly.
    assertEquals("hello-world", slugifyTemplateId("Hello World", emptySet()))
  }

  @Test fun `slug suffixes on collision with an existing id`() {
    assertEquals("mine-2", slugifyTemplateId("Mine", setOf("mine")))
    assertEquals("mine-3", slugifyTemplateId("Mine", setOf("mine", "mine-2")))
  }

  @Test fun `slug never collides with a built-in id`() {
    // "Default" would slug to the built-in "default"; must be suffixed even when existingIds is empty.
    assertEquals("default-2", slugifyTemplateId("Default", emptySet()))
  }

  @Test fun `blank or symbol-only name falls back to a safe id`() {
    assertEquals("template", slugifyTemplateId("   ", emptySet()))
    assertEquals("template", slugifyTemplateId("!!!", emptySet()))
    assertEquals("template-2", slugifyTemplateId("###", setOf("template")))
  }

  @Test fun `slug is length-bounded`() {
    val slug = slugifyTemplateId("x".repeat(500), emptySet())
    assertTrue("slug should be bounded, was ${slug.length}", slug.length <= PromptTemplateStore.MAX_NAME)
  }

  @Test fun `slug stays within the id cap even when suffixed under heavy collision`() {
    // The suffix path shrinks the base to make room for "-N"; even at 3-digit suffixes the id must
    // never exceed the cap. Iteratively collide a max-length base ~150 times to drive the suffix up.
    val longName = "x".repeat(500)
    val taken = mutableSetOf(slugifyTemplateId(longName, emptySet()))
    repeat(150) { taken.add(slugifyTemplateId(longName, taken)) }
    for (id in taken) {
      assertTrue("id '$id' exceeds the cap (len ${id.length})", id.length <= PromptTemplateStore.MAX_NAME)
    }
  }

  // --- isEditable -------------------------------------------------------------------------------

  @Test fun `built-in is not editable, custom is`() {
    assertFalse(isEditable(PromptTemplate("default", "Default", "", builtin = true)))
    assertTrue(isEditable(PromptTemplate("mine", "Mine", "x", builtin = false)))
  }
}
