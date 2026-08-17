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

package cc.grepon.relais.chat

import cc.grepon.relais.data.ReportSendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry policy for opt-in report delivery (#273).
 *
 * These pin the decisions that make the retry safe rather than harmful, and each has a specific way of
 * going wrong that the shipped code got right only deliberately: a 429 counted as an attempt would
 * re-create the "unrecoverable send" the issue is about, and a permanent 4xx treated as retryable would
 * spend the operator's 10-per-hour budget on a request that cannot ever succeed.
 */
class ContentReportRetryTest {

  @Test
  fun `a 2xx is the only success`() {
    assertEquals(ReportSendResult.SENT, classifySendResponse(200))
    assertEquals(ReportSendResult.SENT, classifySendResponse(204))
    assertEquals(ReportSendResult.SENT, classifySendResponse(299))
  }

  @Test
  fun `the Worker's rate-limit refusal is told apart from every other rejection`() {
    // The whole reason send() stopped returning Boolean: 429 must not look like 400 or 500.
    assertEquals(ReportSendResult.RATE_LIMITED, classifySendResponse(429))
  }

  @Test
  fun `server-side faults and timeouts are transient`() {
    assertEquals(ReportSendResult.TRANSIENT, classifySendResponse(500))
    assertEquals(ReportSendResult.TRANSIENT, classifySendResponse(503))
    assertEquals(ReportSendResult.TRANSIENT, classifySendResponse(408))
  }

  @Test
  fun `the Worker's own client-error replies are permanent`() {
    // Every non-429 status report-worker/src/index.ts can reply(): re-sending the identical body to
    // any of these is a guaranteed-useless request against a budget real reports need.
    assertEquals(ReportSendResult.PERMANENT, classifySendResponse(403)) // https required
    assertEquals(ReportSendResult.PERMANENT, classifySendResponse(404)) // not found
    assertEquals(ReportSendResult.PERMANENT, classifySendResponse(405)) // method not allowed
    assertEquals(ReportSendResult.PERMANENT, classifySendResponse(413)) // report too large
    assertEquals(ReportSendResult.PERMANENT, classifySendResponse(400))
  }

  @Test
  fun `a delivered report is terminal and schedules nothing`() {
    val d = dispositionFor(ReportSendResult.SENT, attemptsSoFar = 2)
    assertEquals(ReportSendState.SENT, d.state)
    assertNull("a delivered report must never be retried", d.retryDelayMs)
  }

  @Test
  fun `a permanent rejection fails the row without spending an attempt`() {
    val d = dispositionFor(ReportSendResult.PERMANENT, attemptsSoFar = 1)
    assertEquals(ReportSendState.FAILED, d.state)
    assertNull(d.retryDelayMs)
    assertEquals("a permanent rejection is not worth counting", 1, d.attempts)
  }

  @Test
  fun `a rate-limited attempt stays pending, waits out the window, and costs no attempt`() {
    // The load-bearing one. If a 429 consumed an attempt, a throttled hour would burn the whole
    // budget of MAX_SEND_ATTEMPTS and permanently FAIL a report the Worker would have taken later —
    // reintroducing the unrecoverable send #273 exists to remove.
    val d = dispositionFor(ReportSendResult.RATE_LIMITED, attemptsSoFar = 4)
    assertEquals(ReportSendState.PENDING, d.state)
    assertEquals("a 429 never evaluated the report, so it is not a spent attempt", 4, d.attempts)
    assertNotNull(d.retryDelayMs)
    assertTrue(
      "a retry inside the Worker's 60-minute window is certain to 429 again",
      d.retryDelayMs!! > 60L * 60_000L,
    )
  }

  @Test
  fun `a rate-limited attempt is retryable even at the attempt ceiling`() {
    // Follows from not counting 429s, and is the property that actually protects the operator: being
    // throttled can never exhaust the budget, however many times in a row it happens.
    val d = dispositionFor(ReportSendResult.RATE_LIMITED, attemptsSoFar = MAX_SEND_ATTEMPTS + 3)
    assertEquals(ReportSendState.PENDING, d.state)
    assertNotNull("throttling must never be what permanently fails a report", d.retryDelayMs)
  }

  @Test
  fun `a transient failure spends an attempt and backs off`() {
    val d = dispositionFor(ReportSendResult.TRANSIENT, attemptsSoFar = 0)
    assertEquals(ReportSendState.PENDING, d.state)
    assertEquals(1, d.attempts)
    assertEquals(BASE_RETRY_DELAY_MS, d.retryDelayMs)
  }

  @Test
  fun `transient backoff grows and then stops growing`() {
    val delays =
      (0 until MAX_SEND_ATTEMPTS - 1).map { dispositionFor(ReportSendResult.TRANSIENT, it).retryDelayMs!! }
    assertEquals(
      "each retry must wait longer than the last, up to the ceiling",
      delays.sorted(),
      delays,
    )
    assertTrue("backoff must never exceed the ceiling", delays.all { it <= MAX_RETRY_DELAY_MS })
  }

  @Test
  fun `automatic retries give up at the attempt ceiling`() {
    val d = dispositionFor(ReportSendResult.TRANSIENT, attemptsSoFar = MAX_SEND_ATTEMPTS - 1)
    assertEquals(ReportSendState.FAILED, d.state)
    assertEquals(MAX_SEND_ATTEMPTS, d.attempts)
    assertNull("past the ceiling the retry is manual-only", d.retryDelayMs)
  }

  @Test
  fun `backoff is never negative however high the attempt count goes`() {
    // A raw `BASE shl attempts` overflows Long past ~26 and yields a negative delay, which
    // WorkManager runs immediately — a backoff that becomes a tight loop against the rate limiter.
    // Clamping the exponent, not just the result, is what makes that unreachable.
    for (attempts in 1..64) {
      assertTrue("attempt $attempts produced a non-positive backoff", backoffFor(attempts) > 0)
      assertTrue(backoffFor(attempts) <= MAX_RETRY_DELAY_MS)
    }
  }

  @Test
  fun `a report the operator never opted to send has no send status to show`() {
    // Guards a claim about data egress: a NONE row must not render a line implying it was transmitted.
    assertNull(sendStatusText(ReportSendState.NONE, attempts = 0))
    assertNull(sendStatusText("some-unknown-future-state", attempts = 0))
  }

  @Test
  fun `send status names the state and points at the manual retry once it has failed`() {
    assertEquals("sent to the developer", sendStatusText(ReportSendState.SENT, 1))
    assertEquals("queued to send to the developer", sendStatusText(ReportSendState.PENDING, 0))
    assertTrue(sendStatusText(ReportSendState.PENDING, 2)!!.contains("2 failed attempts"))
    // Singular/plural, because "1 attempts" in the operator's face is the kind of detail that reads
    // as an unfinished product.
    assertTrue(sendStatusText(ReportSendState.FAILED, 1)!!.contains("1 attempt —"))
    assertTrue(sendStatusText(ReportSendState.FAILED, 5)!!.contains("5 attempts"))
    assertTrue(
      "a failed row must tell the operator recovery exists",
      sendStatusText(ReportSendState.FAILED, 5)!!.contains("SEND"),
    )
  }
}
