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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.automation.RelaisIntentAbi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on-device probe for the Tasker/Automate intent ABI (#8) on real hardware (rango / G5).
 *
 * NOTE (issue #44): against the current `Theme.NoDisplay` activity the warm good-token step
 * reproduces the bug — the activity is destroyed before its async decode and never delivers (and the
 * launch hard-crashes under instrumentation on the NoDisplay resume contract). This probe PASSES only
 * after the #44 redesign (decode in a foreground service, deliver via broadcast, drop singleTask,
 * `Theme.Translucent.NoTitleBar`). Cross-app result capture uses the receiver-app method in #44.
 *
 * Captures outcomes via the package-targeted RESULT broadcast the activity emits to `result_package`
 * (set here to this app's own package, so an in-process [BroadcastReceiver] catches it). This avoids
 * ActivityScenario's fragility with the NoDisplay finish-in-onCreate activity, and lets us assert the
 * SECURITY contract directly: a bad token must elicit NO broadcast at all.
 *
 * Matrix (order matters — controlled within one test so the resident-engine state is deterministic):
 *  1. COLD good token  -> ok=false, error="node_not_running"   (cold-start guard; broadcast emitted)
 *  2. COLD bad  token  -> NO broadcast within the window         (unauthorized path suppresses broadcast)
 *  3. warm the resident engine in-process, then
 *  4. WARM good token  -> ok=true, response non-blank, NO token echoed in the result
 *
 * Run (rango / Pixel 10 / G5, E2B staged):
 *   adb -s <serial> shell am instrument -w -e class cc.grepon.relais.TaskerIntentProbe \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 * Watch: adb logcat -s RelaisTaskerProbe
 */
@RunWith(AndroidJUnit4::class)
class TaskerIntentProbe {

  private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
  private val pkg = ctx.packageName

  private fun inferIntent(token: String, requestId: String) =
    Intent(RelaisIntentAbi.ACTION_INFER).apply {
      setClassName(pkg, "cc.grepon.relais.automation.RelaisTaskerActivity")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(RelaisIntentAbi.EXTRA_PROMPT, "Reply with exactly: ok")
      putExtra(RelaisIntentAbi.EXTRA_TOKEN, token)
      putExtra(RelaisIntentAbi.EXTRA_TIMEOUT_MS, 90_000L)
      putExtra(RelaisIntentAbi.EXTRA_REQUEST_ID, requestId)
      // Targeted at our own package so the in-process receiver below catches the RESULT broadcast.
      putExtra(RelaisIntentAbi.EXTRA_RESULT_PACKAGE, pkg)
    }

  /**
   * Fires TWO good-token intents [gapMs] apart and waits up to [waitSec] for both targeted RESULT
   * broadcasts. Returns the captured results keyed by request_id (deduped). Used by the #44/M1
   * concurrency guard: single-flight must admit exactly one decode and still DELIVER it.
   */
  private fun fireTwoAndAwait(
    token: String,
    idA: String,
    idB: String,
    gapMs: Long,
    waitSec: Long,
  ): Map<String, Intent> {
    val results = ConcurrentHashMap<String, Intent>()
    val latch = CountDownLatch(2)
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) {
        val rid = i.getStringExtra(RelaisIntentAbi.EXTRA_REQUEST_ID) ?: return
        if ((rid == idA || rid == idB) && results.putIfAbsent(rid, i) == null) latch.countDown()
      }
    }
    ctx.registerReceiver(
      receiver, IntentFilter(RelaisIntentAbi.ACTION_INFER_RESULT), Context.RECEIVER_NOT_EXPORTED)
    try {
      ctx.startActivity(inferIntent(token, idA))
      Thread.sleep(gapMs) // let A acquire the single-flight latch so B arrives mid-decode
      ctx.startActivity(inferIntent(token, idB))
      latch.await(waitSec, TimeUnit.SECONDS)
      return HashMap(results)
    } finally {
      runCatching { ctx.unregisterReceiver(receiver) }
    }
  }

  /** Fires the intent and waits up to [waitSec] for the targeted RESULT broadcast (null = none). */
  private fun fireAndAwait(token: String, requestId: String, waitSec: Long): Intent? {
    val q = LinkedBlockingQueue<Intent>()
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) {
        if (i.getStringExtra(RelaisIntentAbi.EXTRA_REQUEST_ID) == requestId) q.offer(i)
      }
    }
    val filter = IntentFilter(RelaisIntentAbi.ACTION_INFER_RESULT)
    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    try {
      ctx.startActivity(inferIntent(token, requestId))
      return q.poll(waitSec, TimeUnit.SECONDS)
    } finally {
      runCatching { ctx.unregisterReceiver(receiver) }
    }
  }

  @Test
  fun intentAbiRoundTripOnDevice() {
    val key = RelaisConfig.apiKey(ctx)

    // 1. COLD good token -> node_not_running (engine not resident in this fresh instrument process).
    if (!RelaisEngine.isReady) {
      val cold = fireAndAwait(key, "probe-cold", 15)
      Log.i(TAG, "COLD good-token result=$cold ok=${cold?.getBooleanExtra(RelaisIntentAbi.EXTRA_OK, true)} err=${cold?.getStringExtra(RelaisIntentAbi.EXTRA_ERROR)}")
      assertTrue("cold good-token should broadcast a result", cold != null)
      assertEquals(false, cold?.getBooleanExtra(RelaisIntentAbi.EXTRA_OK, true))
      assertEquals("node_not_running", cold?.getStringExtra(RelaisIntentAbi.EXTRA_ERROR))
      assertNull("result must never echo the token", cold?.getStringExtra(RelaisIntentAbi.EXTRA_TOKEN))
    }

    // 2. COLD bad token -> NO broadcast (unauthorized path sets broadcast=false). Security assertion.
    val unauth = fireAndAwait("WRONG_TOKEN_zzz", "probe-bad", 6)
    Log.i(TAG, "BAD-token broadcast (expect null) = $unauth")
    assertNull("an unauthenticated request must NOT elicit a RESULT broadcast", unauth)

    // 3. Warm the resident engine IN THIS PROCESS so the activity's isReady() gate passes. Direct
    // RelaisEngine.generate bypasses RelaisInference's eager NodeNotReady guard and triggers
    // ensureInitialized() against the persisted model config (E2B on G5).
    val t0 = System.currentTimeMillis()
    RelaisEngine.generate(ctx, RelaisRequest(text = "hi"))
    Log.i(TAG, "warmed resident engine in ${System.currentTimeMillis() - t0} ms; isReady=${RelaisEngine.isReady}")
    assertTrue("engine should be resident after warm-up", RelaisEngine.isReady)

    // 4. WARM good token -> RESULT_OK, non-blank response, NO token echo.
    val ok = fireAndAwait(key, "probe-ok", 90)
    Log.i(TAG, "WARM good-token result ok=${ok?.getBooleanExtra(RelaisIntentAbi.EXTRA_OK, false)} resp=${ok?.getStringExtra(RelaisIntentAbi.EXTRA_RESPONSE)}")
    assertTrue("warm good-token should broadcast a result", ok != null)
    assertTrue("ok flag should be true", ok?.getBooleanExtra(RelaisIntentAbi.EXTRA_OK, false) == true)
    assertTrue("response should be non-blank", !ok?.getStringExtra(RelaisIntentAbi.EXTRA_RESPONSE).isNullOrBlank())
    assertNull("result must never echo the token", ok?.getStringExtra(RelaisIntentAbi.EXTRA_TOKEN))

    // 5. CONCURRENCY (#44 / M1 regression guard): two rapid good-token fires. Single-flight must admit
    // exactly one decode AND still deliver it — the rejected (busy) start must NOT cancel the in-flight
    // decode. Pre-fix, the busy start's stopSelf tore down the shared service instance, killing the
    // running decode, so the successful answer was never delivered (the very #44 symptom).
    val both = fireTwoAndAwait(key, "probe-conc-a", "probe-conc-b", gapMs = 250, waitSec = 95)
    Log.i(TAG, "concurrency results = " +
      both.mapValues { it.value.getBooleanExtra(RelaisIntentAbi.EXTRA_OK, false) to it.value.getStringExtra(RelaisIntentAbi.EXTRA_ERROR) })
    assertEquals("both rapid fires must deliver a result (neither silently dropped)", 2, both.size)
    val oks = both.values.filter { it.getBooleanExtra(RelaisIntentAbi.EXTRA_OK, false) }
    val busies = both.values.filter {
      !it.getBooleanExtra(RelaisIntentAbi.EXTRA_OK, true) &&
        it.getStringExtra(RelaisIntentAbi.EXTRA_ERROR) == RelaisIntentAbi.ERROR_BUSY
    }
    assertEquals("exactly one fire should succeed", 1, oks.size)
    assertEquals("exactly one fire should be shed as busy", 1, busies.size)
    assertTrue(
      "the successful fire must carry a non-blank response",
      !oks.first().getStringExtra(RelaisIntentAbi.EXTRA_RESPONSE).isNullOrBlank())
    assertNull("a busy result must never echo the token", busies.first().getStringExtra(RelaisIntentAbi.EXTRA_TOKEN))
  }

  private companion object {
    const val TAG = "RelaisTaskerProbe"
  }
}
