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
import java.net.URI

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
 * The TOCTOU window that used to remain (validate here, then let `openConnection()` re-resolve the
 * name) is now closed by [resolvePinned]: it hands back the exact [InetAddress] the guard vetted so
 * the fetch ([SkillSourceFetcher]) connects to that pinned IP instead of doing a second lookup —
 * mirroring [cc.grepon.relais.batch.WebhookDelivery]. A name that flips public→private between the
 * check and the connect therefore never gets a second resolution.
 */
object SkillUrlPolicy {

  /** Fixed user-facing rejection message — same wording whatever the specific block reason. */
  const val BLOCK_MESSAGE: String =
    "Skill source must be a public https:// URL. Local, private, loopback, link-local, or " +
      "non-https addresses are blocked (they would let the skill reach internal services)."

  /** Outcome of vetting a skill source: either a connection target pinned to a vetted IP, or a block. */
  sealed interface PinResult {
    /**
     * The skill source passed the SSRF check. Connect to [address]:[port] directly (the pinned IP),
     * sending [host] as the HTTP `Host` header + (for [https]) the TLS SNI, requesting [target].
     */
    data class Pinned(
      val address: InetAddress,
      val host: String,
      val port: Int,
      val https: Boolean,
      val target: String,
    ) : PinResult

    /** The URL is unsafe (or malformed/unresolvable); [message] is user-facing. */
    data class Rejected(val message: String) : PinResult
  }

  /**
   * Vets [url] via [WebhookGuard] and, if allowed, returns the first vetted address as the pin plus the
   * host/port/path needed to issue the request against it. The guard already rejects the host if ANY
   * resolved address is private, so every returned address is safe to connect to. [resolve] is injected
   * for offline tests.
   */
  fun resolvePinned(
    url: String,
    resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
  ): PinResult {
    val uri = runCatching { URI(url) }.getOrNull() ?: return PinResult.Rejected(BLOCK_MESSAGE)
    val host = uri.host ?: return PinResult.Rejected(BLOCK_MESSAGE)
    val https = uri.scheme?.equals("https", ignoreCase = true) == true
    return when (val verdict = WebhookGuard.check(url, resolve = resolve)) {
      is WebhookGuard.Verdict.Blocked -> PinResult.Rejected(BLOCK_MESSAGE)
      is WebhookGuard.Verdict.Allowed -> {
        val address = verdict.addresses.firstOrNull() ?: return PinResult.Rejected(BLOCK_MESSAGE)
        val port = if (uri.port != -1) uri.port else if (https) 443 else 80
        val rawPath = uri.rawPath.orEmpty().ifEmpty { "/" }
        val target = if (uri.rawQuery != null) "$rawPath?${uri.rawQuery}" else rawPath
        PinResult.Pinned(address = address, host = host, port = port, https = https, target = target)
      }
    }
  }

  /** Returns a user-facing error string if [url] is an unsafe skill source, or null if it's allowed. */
  fun validate(
    url: String,
    resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
  ): String? =
    when (val r = resolvePinned(url, resolve)) {
      is PinResult.Pinned -> null
      is PinResult.Rejected -> r.message
    }
}
