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
 * The guard runs at submit time (reject early) AND returns the exact resolved [Verdict.Allowed.addresses]
 * so the delivery layer can **connect to one of those pinned IPs** rather than re-resolving the name. A
 * plain "re-check at delivery" does NOT close the TOCTOU window, because `HttpURLConnection`/the socket
 * would resolve the name a second time — a name that flips to a private IP between the guard's lookup and
 * the connection's lookup (DNS rebinding) would still connect to the private IP. Pinning the validated
 * address (see [WebhookDelivery]) is what actually closes it.
 */
object WebhookGuard {

  sealed interface Verdict {
    /** @param addresses the resolved, vetted IPs — delivery MUST connect to one of these (pinning). */
    data class Allowed(val addresses: List<InetAddress>) : Verdict
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

    // Resolve even on the allowlist path so the delivery layer can still pin the address (the allowlist
    // bypasses the scheme + classification checks, not the pin — that keeps an allowlisted name from
    // being a free re-resolution hole).
    val addresses = runCatching { resolve(host) }.getOrNull()?.takeIf { it.isNotEmpty() }
      ?: return Verdict.Blocked("webhook host does not resolve")

    if (host in allowlist) return Verdict.Allowed(addresses.toList()) // operator's explicit trust

    if (scheme != "https") return Verdict.Blocked("webhook URL must use https (or allowlist the host)")

    for (a in addresses) {
      classify(a)?.let { return Verdict.Blocked("webhook host resolves to a $it address") }
    }
    return Verdict.Allowed(addresses.toList())
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
