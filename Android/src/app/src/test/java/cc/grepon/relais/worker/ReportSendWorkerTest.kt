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

package cc.grepon.relais.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import cc.grepon.relais.chat.ContentReportDraft
import cc.grepon.relais.chat.MAX_SEND_ATTEMPTS
import cc.grepon.relais.chat.ReportSendResult
import cc.grepon.relais.chat.attemptReportSend
import cc.grepon.relais.data.ContentReport
import cc.grepon.relais.data.RelaisDatabase
import cc.grepon.relais.data.ReportSendState
import cc.grepon.relais.data.ReportSurface
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The retry's **wiring**, as opposed to its policy (#273).
 *
 * `ContentReportRetryTest` pins what [cc.grepon.relais.chat.dispositionFor] decides; nothing pinned
 * that the decision is actually carried out — that a retryable failure reaches WorkManager at all,
 * that the worker then drains the row, or that it leaves alone the rows it must never touch. That gap
 * is the same decision-vs-wiring gap `RelaisDownloadRepositoryGateTest` exists to close on the
 * download lane, and it is where a silent regression would live: every unit test would still pass
 * while no report was ever retried.
 *
 * Deterministic and device-free. [WorkManagerTestInitHelper]'s [SynchronousExecutor] runs work on the
 * calling thread and its `TestDriver` satisfies the initial delay on demand, so the 65-minute
 * rate-limit cooldown is exercised in milliseconds with no sleeping and no flake. The sender is
 * overridden throughout: a JVM test on the real one would POST to the production Worker on every CI
 * run.
 */
@RunWith(RobolectricTestRunner::class)
class ReportSendWorkerTest {

  private lateinit var ctx: Context

  /** Records every delivery attempt and answers with a scripted result. */
  private class FakeSender(private val results: MutableList<ReportSendResult>) :
    (ContentReportDraft, String) -> ReportSendResult {
    val excerpts = CopyOnWriteArrayList<String>()

    override fun invoke(draft: ContentReportDraft, surface: String): ReportSendResult {
      excerpts.add(draft.excerpt)
      // Repeat the last scripted result once the script runs out, so a test that only cares about
      // the first attempt doesn't have to enumerate every subsequent one.
      return if (results.size > 1) results.removeAt(0) else results.first()
    }
  }

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      ctx,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    runBlocking { RelaisDatabase.get(ctx).reportDao().clear() }
  }

  @After
  fun tearDown() {
    ReportSendWorker.sender = cc.grepon.relais.chat.ContentReportDelivery::send
    runBlocking { RelaisDatabase.get(ctx).reportDao().clear() }
    RelaisDatabase.resetForTest()
  }

  private suspend fun insertReport(
    sendState: String,
    excerpt: String = "flagged output",
    attempts: Int = 0,
  ): Long =
    RelaisDatabase.get(ctx)
      .reportDao()
      .insert(
        ContentReport(
          reasonId = "other",
          excerpt = excerpt,
          note = null,
          modelId = "m",
          backend = "GPU",
          surface = ReportSurface.CHAT,
          createdAt = 1L,
          sendState = sendState,
          sendAttempts = attempts,
        )
      )

  private fun draft(excerpt: String = "flagged output") =
    ContentReportDraft(
      reasonId = "other",
      excerpt = excerpt,
      note = null,
      modelId = "m",
      backend = "GPU",
    )

  private suspend fun stateOf(id: Long) = RelaisDatabase.get(ctx).reportDao().byId(id)

  private fun enqueuedWork(): List<WorkInfo> =
    WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ReportSendWorker.UNIQUE_WORK).get()

  /**
   * Run one drain pass directly.
   *
   * Deliberately [TestListenableWorkerBuilder] rather than driving the *scheduled* request through
   * `TestDriver`: satisfying the delay and constraints hands execution to WorkManager's own threading,
   * which does not reliably complete inside this test's `runBlocking` — and a worker that never ran
   * turns every assertion about what it did into a **vacuous pass**, which is exactly how the first
   * draft of this file went green while proving nothing. Invoking `doWork()` is synchronous and leaves
   * no room for that. Scheduling is asserted separately, via [enqueuedWork].
   */
  private suspend fun runDrainPass() {
    TestListenableWorkerBuilder<ReportSendWorker>(ctx).build().doWork()
  }

  @Test
  fun `a transient failure schedules a retry that later drains the row to sent`() = runBlocking {
    // The end-to-end path the whole issue is about: first attempt fails on a dead network, and the
    // report still reaches the maintainer later without the operator doing anything.
    val id = insertReport(ReportSendState.PENDING)
    val sender = FakeSender(mutableListOf(ReportSendResult.TRANSIENT, ReportSendResult.SENT))
    ReportSendWorker.sender = sender

    attemptReportSend(
      context = ctx,
      reportId = id,
      draft = draft(),
      surface = ReportSurface.CHAT,
      attemptsSoFar = 0,
      attempt = sender,
    )

    assertEquals(ReportSendState.PENDING, stateOf(id)?.sendState)
    assertEquals(1, stateOf(id)?.sendAttempts)
    val work = enqueuedWork().single()
    assertEquals("a retryable failure must actually reach WorkManager", WorkInfo.State.ENQUEUED, work.state)

    // Run the drain the schedule would eventually have run, without waiting out the backoff.
    runDrainPass()

    assertEquals(ReportSendState.SENT, stateOf(id)?.sendState)
    assertEquals("the retry attempt must be the same report", 2, sender.excerpts.size)
    assertTrue(sender.excerpts.all { it == "flagged output" })
  }

  @Test
  fun `the worker never touches a report the operator did not opt in to send`() = runBlocking {
    // The load-bearing privacy test. A `none` row is every report written before #273 plus every
    // report submitted with the toggle off; if the worker's query ever widened to pick them up, this
    // would transmit reports whose operators never consented and falsify the Data Safety declaration
    // in docs/store-submission.md gate 1. Nothing else in the suite would notice.
    val optedOut = insertReport(ReportSendState.NONE, excerpt = "never opted in")
    val alreadySent = insertReport(ReportSendState.SENT, excerpt = "already delivered")
    val gaveUp = insertReport(ReportSendState.FAILED, excerpt = "terminal failure")
    val sender = FakeSender(mutableListOf(ReportSendResult.SENT))
    ReportSendWorker.sender = sender

    runDrainPass()

    assertEquals("no non-pending row may be transmitted", emptyList<String>(), sender.excerpts.toList())
    assertEquals(ReportSendState.NONE, stateOf(optedOut)?.sendState)
    assertEquals(ReportSendState.SENT, stateOf(alreadySent)?.sendState)
    assertEquals(ReportSendState.FAILED, stateOf(gaveUp)?.sendState)
  }

  @Test
  fun `a permanent rejection is failed on the spot and never rescheduled`() = runBlocking {
    val id = insertReport(ReportSendState.PENDING)
    val sender = FakeSender(mutableListOf(ReportSendResult.PERMANENT))
    ReportSendWorker.sender = sender

    attemptReportSend(
      context = ctx,
      reportId = id,
      draft = draft(),
      surface = ReportSurface.CHAT,
      attemptsSoFar = 0,
      attempt = sender,
    )

    assertEquals(ReportSendState.FAILED, stateOf(id)?.sendState)
    assertEquals(
      "a body the Worker rejected outright must not be retried against the caller budget",
      emptyList<WorkInfo>(),
      enqueuedWork(),
    )
  }

  @Test
  fun `a rate-limited row stays pending and keeps its attempt budget across the worker`() =
    runBlocking {
      // Being throttled must not erode the retry budget — the property ContentReportRetryTest pins on
      // the pure function, asserted here through the real worker so a wiring change can't lose it.
      val id = insertReport(ReportSendState.PENDING, attempts = MAX_SEND_ATTEMPTS - 1)
      val sender = FakeSender(mutableListOf(ReportSendResult.RATE_LIMITED))
      ReportSendWorker.sender = sender

      runDrainPass()

      assertEquals(1, sender.excerpts.size)
      assertEquals(ReportSendState.PENDING, stateOf(id)?.sendState)
      assertEquals(
        "a 429 must not spend an attempt",
        MAX_SEND_ATTEMPTS - 1,
        stateOf(id)?.sendAttempts,
      )
      assertTrue("a throttled row must still be scheduled to retry", enqueuedWork().isNotEmpty())
    }

  @Test
  fun `one run drains at most the per-run cap so a live report keeps some caller budget`() =
    runBlocking {
      // The Worker allows 10 requests/hour per caller. Draining an unbounded backlog would 429 its own
      // tail and, worse, leave nothing for the operator's next live report — whose immediate send
      // would then fail in front of them.
      repeat(9) { insertReport(ReportSendState.PENDING, excerpt = "backlog $it") }
      val sender = FakeSender(mutableListOf(ReportSendResult.SENT))
      ReportSendWorker.sender = sender

      runDrainPass()

      assertTrue(
        "one run sent ${sender.excerpts.size} reports, which would exhaust the hourly budget",
        sender.excerpts.size <= 5,
      )
      // Oldest-first: the report that has waited longest must not be the one perpetually skipped.
      assertEquals("backlog 0", sender.excerpts.first())
    }
}
