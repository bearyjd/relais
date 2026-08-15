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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * constructor default, not a refactor — so no production code path changes to make this testable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelReportTest {

  private val dispatcher = StandardTestDispatcher()
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

  private fun viewModel(sender: FakeSender): ChatViewModel {
    val app = RuntimeEnvironment.getApplication()
    val factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
          ChatViewModel(app, dispatcher, sender) as T
      }
    return ViewModelProvider(store, factory)[ChatViewModel::class.java]
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  @Test
  fun `alsoSend false never invokes the sender, even though the report still saves`() =
    runTest(dispatcher) {
      val sender = FakeSender()
      val vm = viewModel(sender)

      vm.reportContent(turn(), ReportReason.OTHER, "note", alsoSend = false)
      advanceUntilIdle()

      assertEquals(0, sender.calls.get())
      assertEquals("REPORTED — saved on this device", vm.reportNotice.value)
    }

  @Test
  fun `alsoSend true invokes the sender exactly once and reports success in the notice`() =
    runTest(dispatcher) {
      val sender = FakeSender(result = true)
      val vm = viewModel(sender)

      vm.reportContent(turn(), ReportReason.OTHER, "note", alsoSend = true)
      advanceUntilIdle()

      assertEquals(1, sender.calls.get())
      assertEquals("REPORTED — saved on this device and sent to the developer", vm.reportNotice.value)
    }

  @Test
  fun `a failed send is distinguished from a failed save in the notice`() = runTest(dispatcher) {
    val sender = FakeSender(result = false)
    val vm = viewModel(sender)

    vm.reportContent(turn(), ReportReason.OTHER, "note", alsoSend = true)
    advanceUntilIdle()

    assertEquals(1, sender.calls.get())
    assertTrue(vm.reportNotice.value!!.startsWith("REPORTED"))
    assertTrue(vm.reportNotice.value!!.contains("Could not reach the developer"))
  }

  @Test
  fun `an empty turn is rejected before the sender is ever considered`() = runTest(dispatcher) {
    val sender = FakeSender()
    val vm = viewModel(sender)

    vm.reportContent(turn(content = "   "), ReportReason.OTHER, "note", alsoSend = true)
    advanceUntilIdle()

    assertEquals(0, sender.calls.get())
    assertEquals("Nothing to report — that turn is empty.", vm.reportNotice.value)
  }
}
