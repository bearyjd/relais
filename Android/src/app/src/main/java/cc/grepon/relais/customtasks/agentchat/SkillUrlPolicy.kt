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

package cc.grepon.relais.customtasks.agentchat

import cc.grepon.relais.batch.WebhookGuard
import java.net.InetAddress

/**
 * SSRF guard for the "add skill from URL" feature. The node itself fetches the user-supplied URL
 * (`<url>/SKILL.md`, then the skill's scripts that later run as JS in a WebView), so an unguarded URL
 * lets any user make the node reach arbitrary internal services — loopback admin panels, the node's
 * own LAN endpoint, cloud-metadata (`169.254.169.254`), RFC-1918 hosts. Before this guard the fetch
 * was a raw `URL(skillMdUrl).openConnection()` with no scheme/host vetting at all.
 *
 * Reuses [WebhookGuard] (the batch-webhook SSRF check) so both outbound-from-user-URL sinks share one
 * battle-tested policy: **https only**, and the host must NOT resolve to a loopback / wildcard /
 * link-local / site-local (private) / unique-local / multicast address — checked for EVERY resolved
 * address (DNS-rebinding defence) — else blocked; an unresolvable host is blocked (fail closed). No
 * allowlist: skill sources are always public https (local skills use the import path, not this).
 *
 * Pure + offline-testable via the injected [resolve] (see `SkillUrlPolicyTest`).
 *
 * NOTE (residual TOCTOU): this validates the URL but does not yet *pin* the resolved IP for the actual
 * fetch the way [cc.grepon.relais.batch.WebhookDelivery] does, so a name that flips public→private
 * between this check and the `openConnection()` re-resolution is a narrow remaining window. Closing it
 * (pinning the fetch) is a follow-up; this guard already blocks the common cases (literal private IPs,
 * localhost, http, file://).
 */
object SkillUrlPolicy {
  /** Returns a user-facing error string if [url] is an unsafe skill source, or null if it's allowed. */
  fun validate(
    url: String,
    resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
  ): String? =
    when (WebhookGuard.check(url, resolve = resolve)) {
      is WebhookGuard.Verdict.Allowed -> null
      is WebhookGuard.Verdict.Blocked ->
        "Skill source must be a public https:// URL. Local, private, loopback, link-local, or " +
          "non-https addresses are blocked (they would let the skill reach internal services)."
    }
}
