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

/**
 * Pure admission decision for an incoming notification. Extracted from the listener service so the
 * privacy-critical gating (default-deny allowlist, own-package exclusion) is unit-testable without an
 * Android runtime.
 */
object TriageGate {
  /**
   * Whether a posted notification should be buffered for triage.
   *
   * Default-deny: a notification is processed only if triage is enabled AND its package is explicitly
   * on the [allowlist]. The node's own notifications are always excluded (no feedback loop), as are
   * ongoing/foreground-service notifications (persistent status, not events) and group-summary rows
   * (duplicates of their children).
   */
  fun shouldProcess(
    enabled: Boolean,
    allowlist: Set<String>,
    ownPackage: String,
    pkg: String,
    isOngoing: Boolean,
    isGroupSummary: Boolean,
  ): Boolean {
    if (!enabled) return false
    if (pkg == ownPackage) return false
    if (pkg !in allowlist) return false
    if (isOngoing) return false
    if (isGroupSummary) return false
    return true
  }
}
