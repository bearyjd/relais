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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Toggles the DEFAULT-OFF server-side session memory flag (Feature #5) on-device, so the manual
 * two-request HTTP recall can be validated against a live node, then turned back off.
 *
 * Writes the SAME prefs file + key that [RelaisConfig.sessionMemoryEnabled] reads ("relais" /
 * "session_memory_enabled") with a SYNCHRONOUS commit(), so the value is on disk before the node
 * re-reads it on its next boot (the running node reads the flag per request, so a restart picks it up).
 *
 *   adb -s <serial> shell am instrument -w -e class cc.grepon.relais.SessionGateProbe#enableSessionMemory \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SessionGateProbe {

  private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

  private fun setFlag(enabled: Boolean) {
    ctx.getSharedPreferences("relais", Context.MODE_PRIVATE).edit()
      .putBoolean("session_memory_enabled", enabled).commit()
  }

  @Test fun enableSessionMemory() {
    setFlag(true)
    assertTrue("session memory should read enabled", RelaisConfig.sessionMemoryEnabled(ctx))
  }

  @Test fun disableSessionMemory() {
    setFlag(false)
    assertFalse("session memory should read disabled", RelaisConfig.sessionMemoryEnabled(ctx))
  }
}
