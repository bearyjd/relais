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

package cc.grepon.relais.triage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TriageConfigTest {
  private lateinit var context: Context

  @Before fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    context.getSharedPreferences("relais", Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test fun `defaults are privacy-preserving`() {
    assertFalse(TriageConfig.enabled(context))
    assertFalse(TriageConfig.consented(context))
    assertTrue(TriageConfig.allowlist(context).isEmpty())
    assertEquals(TriageConfig.DEFAULT_INTERVAL_MIN.toLong(), TriageConfig.intervalMinutes(context))
    // Urgent surfacing defaults on (the chosen behavior) but only matters once triage is enabled.
    assertTrue(TriageConfig.urgentEnabled(context))
  }

  @Test fun `enabled flag round-trips`() {
    TriageConfig.setEnabled(context, true)
    assertTrue(TriageConfig.enabled(context))
    TriageConfig.setEnabled(context, false)
    assertFalse(TriageConfig.enabled(context))
  }

  @Test fun `allowlist round-trips and is cleaned`() {
    TriageConfig.setAllowlist(context, setOf(" com.b.app ", "com.a.app", "", "com.a.app"))
    assertEquals(setOf("com.a.app", "com.b.app"), TriageConfig.allowlist(context))
  }

  @Test fun `interval is clamped on read and write`() {
    TriageConfig.setIntervalMinutes(context, 5)
    assertEquals(TriageConfig.MIN_INTERVAL_MIN.toLong(), TriageConfig.intervalMinutes(context))
    TriageConfig.setIntervalMinutes(context, 99999)
    assertEquals(TriageConfig.MAX_INTERVAL_MIN.toLong(), TriageConfig.intervalMinutes(context))
    TriageConfig.setIntervalMinutes(context, 90)
    assertEquals(90L, TriageConfig.intervalMinutes(context))
  }

  @Test fun `consented flag round-trips`() {
    assertFalse(TriageConfig.consented(context))
    TriageConfig.setConsented(context, true)
    assertTrue(TriageConfig.consented(context))
  }
}
