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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.data.RelaisDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for the optional server-side session memory (Feature #5). DEFERRED this session —
 * documents the manual two-request HTTP recall and exercises the real file-backed [RelaisDatabase]
 * round-trip so the persistence path is proven on hardware.
 *
 * Manual HTTP recall (the headline acceptance check) on a live node:
 *   1. Enable session memory in the Relais Node control panel (or set the flag).
 *   2. First request carrying `-H 'X-Relais-Session: probe'` with a single user message that states
 *      a fact (e.g. "my budget is two thousand dollars").
 *   3. Second request on the SAME `X-Relais-Session: probe` asking the model to recall that fact —
 *      with NO prior messages[] history. The stored turn should be injected and recalled.
 *
 * Run (rango / Pixel 10):
 *   adb -s <serial> shell am instrument -w \
 *     -e class cc.grepon.relais.SessionMemoryProbe \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Watch: adb logcat -s RelaisSessionProbe
 */
@RunWith(AndroidJUnit4::class)
class SessionMemoryProbe {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun probeStoreRoundTripOnDevice() {
    // Always runnable on a device — no model required. The HTTP two-request recall is the manual step.
    assumeTrue("Robolectric-only environment", true)
    val key = "probe:" + System.currentTimeMillis()
    runBlocking {
      RelaisSessionStore.record(context, key, "my budget is two thousand dollars", "Noted: 2000 USD.")
      val history = RelaisSessionStore.loadHistory(context, key, budget = 10)
      Log.i(TAG, "loaded ${history.size} turns for $key: ${history.map { it.role to it.text.take(40) }}")
      assertEquals(2, history.size)
      assertEquals("user", history.first().role)
      assertTrue(history.first().text.contains("two thousand"))
      assertEquals("assistant", history[1].role)
      // Clean up so the probe leaves no residue on the device DB.
      RelaisSessionStore.clear(context, key)
      assertEquals(0, RelaisSessionStore.count(context, key))
    }
    // Drop the singleton so the probe doesn't leak a handle to the production DB file.
    RelaisDatabase.resetForTest()
  }

  private companion object {
    const val TAG = "RelaisSessionProbe"
  }
}
