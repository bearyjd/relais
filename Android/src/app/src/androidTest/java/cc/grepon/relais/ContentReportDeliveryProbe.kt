/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the terms of the GNU Affero
 * General Public License as published by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General
 * Public License for more details.
 */

package cc.grepon.relais

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.chat.ContentReportDelivery
import cc.grepon.relais.chat.ContentReportDraft
import cc.grepon.relais.chat.ReportSendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for #258's send path: does [ContentReportDelivery.send] actually reach the deployed
 * `report.ventouxlabs.com` Worker and get a 2xx back — response-code handling, the disabled-redirect
 * setting, and connect/read timeouts only exercise against a real socket, which
 * `ContentReportDeliveryTest` (JVM, payload-shaping only) cannot reach.
 *
 * **This POSTs a real report to production KV.** There is no delete-by-report API from the client, so
 * running this leaves a live row until its 180-day TTL — clean it up afterward the same way any manual
 * verification does: `npx wrangler kv key get --binding REPORTS --remote '<key>'` to find it (excerpt
 * is `"on-device probe verification"`), then `npx wrangler kv key delete --binding REPORTS --remote
 * '<key>'` from `report-worker/`.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.ContentReportDeliveryProbe -e RELAIS_PROBE 1 \
 *     com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ContentReportDeliveryProbe {

  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun sendReachesTheDeployedWorkerAndReturnsTrueOn2xx() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")

    val draft =
      ContentReportDraft(
        reasonId = "other",
        excerpt = "on-device probe verification",
        note = null,
        modelId = null,
        backend = null,
      )
    // Blocking by contract (see ContentReportDelivery.send's KDoc) — instrumented tests already run
    // off the main thread, so no explicit dispatcher hop is needed here.
    val result = ContentReportDelivery.send(draft, surface = "chat")
    // Asserts the classified result (#273), not a boolean: a failure now names WHICH failure, so a
    // probe run distinguishes "the Worker throttled this caller" from "the deploy is broken" without
    // going to logcat. RATE_LIMITED in particular is an expected outcome when this probe is re-run
    // more than ten times in an hour, and reads very differently from a 5xx.
    assertEquals(
      "send() returned $result — check logcat tag RelaisReportDelivery for the HTTP code",
      ReportSendResult.SENT,
      result,
    )
  }
}
