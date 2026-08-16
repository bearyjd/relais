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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import cc.grepon.relais.chat.ContentReportDraft
import cc.grepon.relais.chat.ReportReason
import cc.grepon.relais.data.ChatTurn
import cc.grepon.relais.data.RelaisDatabase
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the one guarantee the #258 send path exists to make: `alsoSend = false` never reaches the
 * network. Every other layer (dialog default, docs, Data Safety "optional" declaration) rests on
 * this actually holding in [ChatViewModel.reportContent] — reviewed code without a red/green test
 * only confirms it reads plausibly, per this repo's own recorded lesson that a save/report path can
 * ship broken with every other layer green.
 *
 * `sendReport` is injected the same way [ChatViewModelSpeechTest] injects a test dispatcher — a
 * constructor default, not a refactor.
 *
 * Deliberately NOT [kotlinx.coroutines.test.runTest] / [kotlinx.coroutines.test.StandardTestDispatcher]:
 * `reportContent` goes through [cc.grepon.relais.chat.persistContentReport], a real Room write, and
 * Room dispatches suspend queries on its own internal executor regardless of which
 * `CoroutineDispatcher` the caller is on — a real thread hop no amount of `advanceUntilIdle()` can
 * see, so the first version of this file raced and failed nondeterministically in CI (all three tests
 * that reach the `Valid` branch; the one that short-circuits before touching Room passed). Plain
 * `runBlocking` + awaiting the actual [ChatViewModel.reportNotice] emission sidesteps that: it waits
 * for what really happened, not for a virtual clock's idea of "done".
 */
@RunWith(RobolectricTestRunner::class)
class ChatViewModelReportTest {

  private val store = ViewModelStore()

  private fun turn(content: String = "flagged output") =
    ChatTurn(
      id = "a",
      conversationId = "conv",
      role = "assistant",
      content = content,
      attachmentType = null,
      attachmentPath = null,
      answeredByModelId = "test-model",
      answeredByBackend = "GPU",
      createdAt = 1L,
    )

  /** A `sendReport` fake that records whether it was ever invoked, and what it returns. */
  private class FakeSender(private val result: Boolean = true) : (ContentReportDraft, String) -> Boolean {
    val calls = AtomicInteger(0)

    override fun invoke(draft: ContentReportDraft, surface: String): Boolean {
      calls.incrementAndGet()
      return result
    }
  }

  private fun viewModel(sender: (ContentReportDraft, String) -> Boolean): ChatViewModel {
    val app = RuntimeEnvironment.getApplication()
    val factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(app, Dispatchers.IO, sender) as T
      }
    return ViewModelProvider(store, factory)[ChatViewModel::class.java]
  }

  /**
   * Waits (real time, bounded) for [ChatViewModel.reportNotice] to reach [expected], then asserts
   * against whatever it actually settled on — so a wrong value fails with a normal expected/actual
   * diff instead of an opaque timeout, whether it arrived late or never arrived at all.
   */
  private suspend fun awaitNotice(vm: ChatViewModel, expected: String) {
    withTimeoutOrNull(5_000) { vm.reportNotice.first { it == expected } }
    assertEquals(expected, vm.reportNotice.value)
  }

  @Before
  fun setUp() {
    // Real, not virtual: viewModelScope needs Dispatchers.Main to resolve to something, but nothing
    // here is pumped by a test scheduler — see the class doc for why.
    Dispatchers.setMain(Dispatchers.Unconfined)
  }

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
    // reportContent goes through the real, file-backed RelaisDatabase singleton (not an in-memory
    // instance), which is a plain companion-object var — Robolectric does not reset it automatically.
    // Without this, inserted report rows and the open connection leak into whichever test runs next
    // in this JVM/classloader, matching the cleanup RelaisSessionStoreTest.kt already established for
    // the same singleton.
    RelaisDatabase.resetForTest()
    RuntimeEnvironment.getApplication().deleteDatabase("relais.db")
  }

  @Test
  fun `alsoSend false never invokes the sender, even though the report still saves`() = runBlocking {
    val sender = FakeSender()
    val vm = viewModel(sender)

    vm.reportContent(turn(), ReportReason.OTHER, "note", alsoSend = false)
    awaitNotice(vm, "REPORTED — saved on this device")

    assertEquals(0, sender.calls.get())
    // Guards against a race, not just a wrong value: alsoSend=false skips the network branch
    // entirely, so reportNotice should never change again. Without this, a mutant that ignored
    // alsoSend and always sent could still read calls==0 here if the real IO-dispatcher hop for the
    // (wrongly attempted) send simply hadn't incremented the counter yet at the moment this line ran.
    val strayUpdate = withTimeoutOrNull(300) { vm.reportNotice.first { it != "REPORTED — saved on this device" } }
    assertNull("reportNotice changed again after the save-only notice — a send was attempted", strayUpdate)
    assertEquals(0, sender.calls.get())
  }

  @Test
  fun `a failed save never invokes the sender even when alsoSend is true`() = runBlocking {
    val sender = FakeSender()
    val app = RuntimeEnvironment.getApplication()
    val vm = viewModel(sender)
    // Forces the next Room write to fail; persistContentReport's runCatching turns that into
    // saved=false, which is the actual guard reportContent relies on (`if (saved && alsoSend)`) —
    // the converse of the alsoSend=false case above, and previously unverified.
    RelaisDatabase.get(app).close()

    vm.reportContent(turn(), ReportReason.OTHER, "note", alsoSend = true)
    awaitNotice(vm, "Could not save that report.")

    assertEquals(0, sender.calls.get())
  }

  @Test
  fun `alsoSend true invokes the sender exactly once and reports success in the notice`() = runBlocking {
    val sender = FakeSender(result = true)
    val vm = viewModel(sender)

    vm.reportContent(turn(), ReportReason.OTHER, "note", alsoSend = true)
    awaitNotice(vm, "REPORTED — saved on this device and sent to the developer")

    assertEquals(1, sender.calls.get())
  }

  @Test
  fun `a failed send is distinguished from a failed save in the notice`() = runBlocking {
    val sender = FakeSender(result = false)
    val vm = viewModel(sender)

    vm.reportContent(turn(), ReportReason.OTHER, "note", alsoSend = true)
    awaitNotice(vm, "REPORTED — saved on this device. Could not reach the developer.")

    assertEquals(1, sender.calls.get())
  }

  @Test
  fun `an empty turn is rejected before the sender is ever considered`() = runBlocking {
    val sender = FakeSender()
    val vm = viewModel(sender)

    vm.reportContent(turn(content = "   "), ReportReason.OTHER, "note", alsoSend = true)
    awaitNotice(vm, "Nothing to report — that turn is empty.")

    assertEquals(0, sender.calls.get())
  }

  /** A `sendReport` fake whose delay and result depend on which report's content invoked it. */
  private class PerReportSender(private val behaviorByExcerpt: Map<String, Pair<Long, Boolean>>) :
    (ContentReportDraft, String) -> Boolean {
    override fun invoke(draft: ContentReportDraft, surface: String): Boolean {
      val (delayMs, result) = behaviorByExcerpt.getValue(draft.excerpt)
      Thread.sleep(delayMs) // send() is a blocking function by contract; a real sleep is honest here.
      return result
    }
  }

  @Test
  fun `a late outcome from a superseded report never overwrites the newest report's notice`() = runBlocking {
    // Report A's send is slow and fails; report B's is instant and succeeds. If reportContent didn't
    // guard notice writes by generation, A's late failure would land after B's success and silently
    // overwrite it — telling the operator the wrong report's outcome, exactly the bug the adversarial
    // review pass found: the send-outcome notice is the operator's one signal for whether a specific
    // report (which may carry a name or other detail typed into its note) actually left the device.
    val sender = PerReportSender(mapOf("first turn" to (400L to false), "second turn" to (0L to true)))
    val vm = viewModel(sender)

    vm.reportContent(turn(content = "first turn"), ReportReason.OTHER, "note", alsoSend = true)
    delay(50) // let report A pass its immediate "saved" notice and enter its slow, failing send
    vm.reportContent(turn(content = "second turn"), ReportReason.OTHER, "note", alsoSend = true)

    awaitNotice(vm, "REPORTED — saved on this device and sent to the developer") // B's outcome
    delay(500) // long enough for A's slow, failing send to resolve and, if unguarded, clobber it

    assertEquals(
      "report A's late, failed send overwrote report B's more recent, successful one",
      "REPORTED — saved on this device and sent to the developer",
      vm.reportNotice.value,
    )
  }
}
