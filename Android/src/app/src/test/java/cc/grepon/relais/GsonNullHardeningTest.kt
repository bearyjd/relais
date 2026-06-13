/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import cc.grepon.relais.data.ModelAllowlist
import cc.grepon.relais.data.RelaisModelRef
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Gson null-hardening regression tests — issue #13.
 *
 * Three fix instances, each tested RED-then-GREEN:
 *   OQ1: allowlist-match boot path wraps NPE as handled IllegalStateException via safeToModel().
 *   M1:  curatedModelsFrom drops bad entries rather than crashing on null taskTypes/modelFile.
 *   M2:  RelaisModelRef.fromJson rejects absent/blank displayName.
 *
 * All pure JVM — no Robolectric; Build.VERSION.SDK_INT returns 0 (isReturnDefaultValues=true)
 * so the per-SOC block in toModel() is skipped, and isPixelDevice()/isPixel10() return false.
 */
class GsonNullHardeningTest {

  // ---------------------------------------------------------------------------
  // OQ1 — safeToModel wraps NPE from a malformed allowlist entry
  // ---------------------------------------------------------------------------

  /**
   * A fixture allowlist entry that is missing `taskTypes` — Gson leaves that non-null List field as
   * actual null at runtime. When toModel() calls `taskTypes.contains(...)` it throws a
   * NullPointerException unconditionally (not gated on any android.os.Build stub), so this fixture
   * guarantees the test is genuinely RED without the safeToModel runCatching wrap and GREEN with it.
   */
  private val oq1MalformedAllowlistJson =
    """
    {
      "models": [
        { "name": "Bad Entry", "modelId": "litert-community/bad-entry",
          "modelFile": "bad.litertlm", "commitHash": "bad000",
          "description": "missing taskTypes", "sizeInBytes": 100, "defaultConfig": {} }
      ]
    }
    """
      .trimIndent()

  private val oq1GoodAllowlistJson =
    """
    {
      "models": [
        { "name": "Good Entry", "modelId": "litert-community/good-entry",
          "modelFile": "good.litertlm", "commitHash": "abc111",
          "description": "A fine model", "sizeInBytes": 3600000000,
          "defaultConfig": { "accelerators": "cpu" },
          "taskTypes": ["llm_chat"], "runtimeType": "litert_lm" }
      ]
    }
    """
      .trimIndent()

  /**
   * Omitting `taskTypes` guarantees toModel() NPEs on `taskTypes.contains(...)` — this is not
   * gated on any android stub so it is deterministically RED without the fix. safeToModel must
   * convert that NPE to an IllegalStateException (the existing error() contract). Any other
   * exception type — especially NullPointerException — is a regression.
   */
  @Test
  fun `OQ1 safeToModel on null-taskTypes entry throws IllegalStateException with malformed message`() {
    val allowlist = Gson().fromJson(oq1MalformedAllowlistJson, ModelAllowlist::class.java)
    val allowed = allowlist.models.first()
    val url = "https://example.com/allowlist.json"

    try {
      RelaisModelProvisioner.safeToModel(allowed, allowed.modelId, url)
      fail("Expected IllegalStateException — safeToModel must not return normally for a null-taskTypes entry")
    } catch (e: IllegalStateException) {
      assertTrue(
        "Expected 'malformed' in ISE message, got: ${e.message}",
        e.message?.contains("malformed") == true,
      )
    } catch (e: NullPointerException) {
      throw AssertionError(
        "OQ1 REGRESSION: safeToModel propagated a raw NullPointerException — runCatching wrap missing",
        e,
      )
    }
  }

  @Test
  fun `OQ1 safeToModel on well-formed entry returns a Model without throwing`() {
    val allowlist = Gson().fromJson(oq1GoodAllowlistJson, ModelAllowlist::class.java)
    val allowed = allowlist.models.first()
    val model =
      RelaisModelProvisioner.safeToModel(allowed, allowed.modelId, "https://example.com/al.json")
    assertNotNull("safeToModel must return a Model for a well-formed entry", model)
    assertEquals("Good Entry", model.name)
  }

  // ---------------------------------------------------------------------------
  // M1 — curatedModelsFrom drops entries with null taskTypes or modelFile
  // ---------------------------------------------------------------------------

  private val m1MixedAllowlistJson =
    """
    {
      "models": [
        { "name": "Good", "modelId": "litert-community/good",
          "modelFile": "good.litertlm", "commitHash": "g111", "description": "",
          "sizeInBytes": 1000, "defaultConfig": {},
          "taskTypes": ["llm_chat"], "runtimeType": "litert_lm" },
        { "name": "No TaskTypes", "modelId": "litert-community/no-task-types",
          "modelFile": "nt.litertlm", "commitHash": "n222", "description": "",
          "sizeInBytes": 1000, "defaultConfig": {},
          "runtimeType": "litert_lm" },
        { "name": "No ModelFile", "modelId": "litert-community/no-model-file",
          "commitHash": "nf333", "description": "",
          "sizeInBytes": 1000, "defaultConfig": {},
          "taskTypes": ["llm_chat"], "runtimeType": "litert_lm" }
      ]
    }
    """
      .trimIndent()

  @Test
  fun `M1 curatedModelsFrom drops null-field entries and returns only the good ones`() {
    val allowlist = Gson().fromJson(m1MixedAllowlistJson, ModelAllowlist::class.java)

    // Must not throw — must drop bad entries and return the one good one.
    val refs =
      try {
        RelaisModelCatalog.curatedModelsFrom(allowlist)
      } catch (e: NullPointerException) {
        throw AssertionError(
          "M1 REGRESSION: curatedModelsFrom threw NullPointerException: ${e.message}",
          e,
        )
      }

    val ids = refs.map { it.modelId }
    assertTrue("good entry kept", ids.contains("litert-community/good"))
    assertTrue("null-taskTypes entry dropped", !ids.contains("litert-community/no-task-types"))
    assertTrue("null-modelFile entry dropped", !ids.contains("litert-community/no-model-file"))
    assertEquals("exactly 1 runnable model survives", 1, refs.size)
  }

  // ---------------------------------------------------------------------------
  // M2 — fromJson rejects absent/blank displayName
  // ---------------------------------------------------------------------------

  @Test
  fun `M2 fromJson returns null when displayName is absent`() {
    val json =
      """{"modelId":"a/b","modelFile":"m.litertlm","commitHash":"abc","sizeInBytes":1,"source":"huggingface"}"""
    val ref = RelaisModelRef.fromJson(json)
    assertNull("fromJson must return null when displayName is absent", ref)
  }

  @Test
  fun `M2 fromJson returns null when displayName is blank`() {
    val json =
      """{"modelId":"a/b","modelFile":"m.litertlm","commitHash":"abc","sizeInBytes":1,"source":"huggingface","displayName":"   "}"""
    val ref = RelaisModelRef.fromJson(json)
    assertNull("fromJson must return null when displayName is blank", ref)
  }

  @Test
  fun `M2 fromJson returns non-null ref when displayName is present`() {
    val json =
      """{"modelId":"a/b","modelFile":"m.litertlm","commitHash":"abc","sizeInBytes":1,"source":"huggingface","displayName":"My Model"}"""
    val ref = RelaisModelRef.fromJson(json)
    assertNotNull("fromJson must return a non-null ref when displayName is present", ref)
    assertEquals("My Model", ref?.displayName)
  }
}
