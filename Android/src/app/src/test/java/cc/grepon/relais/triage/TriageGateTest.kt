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

package cc.grepon.relais.triage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageGateTest {
  private val own = "cc.grepon.relais"
  private val allow = setOf("com.bank.app", "com.chat.app")

  private fun gate(
    enabled: Boolean = true,
    pkg: String = "com.bank.app",
    isOngoing: Boolean = false,
    isGroupSummary: Boolean = false,
  ) = TriageGate.shouldProcess(enabled, allow, own, pkg, isOngoing, isGroupSummary)

  @Test fun `allowlisted event notification is processed`() {
    assertTrue(gate())
  }

  @Test fun `disabled blocks everything`() {
    assertFalse(gate(enabled = false))
  }

  @Test fun `own package is never processed`() {
    assertFalse(gate(pkg = own))
  }

  @Test fun `non-allowlisted package is denied by default`() {
    assertFalse(gate(pkg = "com.tracker.ads"))
  }

  @Test fun `ongoing notifications are skipped`() {
    assertFalse(gate(isOngoing = true))
  }

  @Test fun `group summary rows are skipped`() {
    assertFalse(gate(isGroupSummary = true))
  }

  @Test fun `empty allowlist denies all`() {
    assertFalse(TriageGate.shouldProcess(true, emptySet(), own, "com.bank.app", false, false))
  }
}
