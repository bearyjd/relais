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

package cc.grepon.relais.chat

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [deliverReport] is the one gate-and-sequence rule both [cc.grepon.relais.ChatViewModel] (Relais
 * chat) and `cc.grepon.relais.ui.common.chat.ChatPanel` (Gallery/agent chat) route through — pinning
 * it here covers the invariant for both callers, including the Gallery/agent surface, which has no
 * ViewModel of its own to attach a seam test to the way [cc.grepon.relais.ChatViewModelReportTest]
 * does for the Relais surface.
 *
 * Pure suspend logic, no Android/Room dependency — plain `runBlocking` is enough.
 */
class ContentReportOutcomeTest {

  private fun draft(reasonId: String = "other") =
    ContentReportDraft(reasonId = reasonId, excerpt = "flagged output", note = null, modelId = null, backend = null)

  private class FakeSender(private val result: Boolean = true) {
    val calls = AtomicInteger(0)
    val send: suspend (ContentReportDraft, String) -> Boolean = { _, _ ->
      calls.incrementAndGet()
      result
    }
  }

  @Test
  fun `alsoSend false never invokes send and reports SAVED_ONLY once`() = runBlocking {
    val sender = FakeSender()
    val outcomes = mutableListOf<ReportOutcome>()

    deliverReport(saved = true, alsoSend = false, draft = draft(), surface = "chat", send = sender.send) {
      outcomes.add(it)
    }

    assertEquals(0, sender.calls.get())
    assertEquals(listOf(ReportOutcome.SAVED_ONLY), outcomes)
  }

  @Test
  fun `alsoSend true invokes send exactly once and reports SAVED_ONLY then SAVED_AND_SENT`() = runBlocking {
    val sender = FakeSender(result = true)
    val outcomes = mutableListOf<ReportOutcome>()

    deliverReport(saved = true, alsoSend = true, draft = draft(), surface = "chat", send = sender.send) {
      outcomes.add(it)
    }

    assertEquals(1, sender.calls.get())
    assertEquals(listOf(ReportOutcome.SAVED_ONLY, ReportOutcome.SAVED_AND_SENT), outcomes)
  }

  @Test
  fun `a failed send reports SAVED_SEND_FAILED, not SAVED_AND_SENT`() = runBlocking {
    val sender = FakeSender(result = false)
    val outcomes = mutableListOf<ReportOutcome>()

    deliverReport(saved = true, alsoSend = true, draft = draft(), surface = "chat", send = sender.send) {
      outcomes.add(it)
    }

    assertEquals(1, sender.calls.get())
    assertEquals(listOf(ReportOutcome.SAVED_ONLY, ReportOutcome.SAVED_SEND_FAILED), outcomes)
  }

  @Test
  fun `saved false never invokes send regardless of alsoSend`() = runBlocking {
    val sender = FakeSender()
    val outcomes = mutableListOf<ReportOutcome>()

    deliverReport(saved = false, alsoSend = true, draft = draft(), surface = "chat", send = sender.send) {
      outcomes.add(it)
    }

    assertEquals(0, sender.calls.get())
    assertEquals(listOf(ReportOutcome.SAVE_FAILED), outcomes)
  }

  @Test
  fun `a null draft is treated as save-failed even if saved is somehow true`() = runBlocking {
    val sender = FakeSender()
    val outcomes = mutableListOf<ReportOutcome>()

    deliverReport(saved = true, alsoSend = true, draft = null, surface = "chat", send = sender.send) {
      outcomes.add(it)
    }

    assertEquals(0, sender.calls.get())
    assertEquals(listOf(ReportOutcome.SAVE_FAILED), outcomes)
  }
}
