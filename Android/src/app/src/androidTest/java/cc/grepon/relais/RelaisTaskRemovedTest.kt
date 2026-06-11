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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task-removal hardening: [RelaisNodeService.onTaskRemoved] must re-arm the crash/OOM watchdog
 * heartbeat so a swiped-away / recents-swept node keeps its recovery alarm — but only when the
 * operator means the node to run ([RelaisConfig.shouldRun]). We assert the real AlarmManager
 * PendingIntent the watchdog uses, reconstructed identically here (requestCode 0,
 * [RelaisWatchdogReceiver], IMMUTABLE); `FLAG_NO_CREATE` returns non-null only if that intent
 * currently exists, and [PendingIntent.cancel] gives each case a known-clear baseline.
 */
@RunWith(AndroidJUnit4::class)
class RelaisTaskRemovedTest {

  private fun watchdogPi(ctx: Context, flags: Int): PendingIntent? =
    PendingIntent.getBroadcast(
      ctx,
      0,
      Intent(ctx, RelaisWatchdogReceiver::class.java),
      flags or PendingIntent.FLAG_IMMUTABLE,
    )

  /** Drop any existing watchdog PendingIntent so each assertion starts from a known-clear baseline. */
  private fun clearWatchdogPi(ctx: Context) {
    watchdogPi(ctx, PendingIntent.FLAG_NO_CREATE)?.cancel()
  }

  @Test
  fun reArmsWatchdogOnlyWhenShouldRun() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val savedRun = RelaisConfig.shouldRun(ctx) // targetContext = real prefs; restore in finally
    try {
      // Stopped node: task removal must NOT arm the watchdog (don't revive a node the operator stopped).
      clearWatchdogPi(ctx)
      RelaisConfig.setShouldRun(ctx, false)
      RelaisNodeService.reArmWatchdogIfShouldRun(ctx)
      assertNull(
        "must not arm the watchdog when the node is stopped",
        watchdogPi(ctx, PendingIntent.FLAG_NO_CREATE),
      )

      // Running node: task removal must re-arm the watchdog heartbeat.
      clearWatchdogPi(ctx)
      RelaisConfig.setShouldRun(ctx, true)
      RelaisNodeService.reArmWatchdogIfShouldRun(ctx)
      assertNotNull(
        "must re-arm the watchdog when the node should be running",
        watchdogPi(ctx, PendingIntent.FLAG_NO_CREATE),
      )
    } finally {
      clearWatchdogPi(ctx)
      RelaisConfig.setShouldRun(ctx, savedRun)
      // Restore the live node's heartbeat if it was meant to be running (we disturbed it above).
      if (savedRun) RelaisWatchdog.schedule(ctx)
    }
  }
}
