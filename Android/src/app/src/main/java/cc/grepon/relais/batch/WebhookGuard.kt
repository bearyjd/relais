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

package cc.grepon.relais.batch

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * SSRF guard for batch webhook destinations. The node makes the outbound request, so an unguarded
 * webhook URL would let any API-key holder make the node hit arbitrary internal hosts (cloud metadata
 * endpoints, the LAN, loopback admin panels). Policy:
 *  - the URL must be `https` (a webhook carries the result + a signature — no cleartext), UNLESS the
 *    host is on the operator's allowlist (for a trusted internal http endpoint);
 *  - the host must NOT resolve to a loopback / any-local / link-local / site-local (private) /
 *    unique-local / multicast address — checked for EVERY resolved address (DNS-rebinding defence) —
 *    UNLESS the host is allowlisted;
 *  - an unresolvable host is blocked (fail closed).
 *
 * This guard runs both at submit time (reject early) and again at delivery time (a name can re-resolve
 * to a private IP between the two — TOCTOU).
 */
object WebhookGuard {

  sealed interface Verdict {
    data object Allowed : Verdict
    data class Blocked(val reason: String) : Verdict
  }

  /**
   * @param allowlist operator-trusted hostnames (lowercased) that bypass the https + private-IP checks.
   * @param resolve injectable resolver (defaults to DNS); tests pass literal-IP maps.
   */
  fun check(
    urlString: String,
    allowlist: Set<String> = emptySet(),
    resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
  ): Verdict {
    val uri = runCatching { URI(urlString) }.getOrNull()
      ?: return Verdict.Blocked("malformed webhook URL")
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
      ?: return Verdict.Blocked("webhook URL has no host")

    if (host in allowlist) return Verdict.Allowed // operator's explicit trust — bypass scheme + IP checks

    if (scheme != "https") return Verdict.Blocked("webhook URL must use https (or allowlist the host)")

    val addresses = runCatching { resolve(host) }.getOrNull()?.takeIf { it.isNotEmpty() }
      ?: return Verdict.Blocked("webhook host does not resolve")
    for (a in addresses) {
      classify(a)?.let { return Verdict.Blocked("webhook host resolves to a $it address") }
    }
    return Verdict.Allowed
  }

  /** Block reason for a single address, or null if it's a routable public address. */
  fun classify(a: InetAddress): String? = when {
    a.isLoopbackAddress -> "loopback"
    a.isAnyLocalAddress -> "wildcard"
    a.isLinkLocalAddress -> "link-local"
    a.isSiteLocalAddress -> "private" // 10/8, 172.16/12, 192.168/16
    a.isMulticastAddress -> "multicast"
    a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC -> "unique-local" // fc00::/7 (ULA)
    else -> null
  }
}
