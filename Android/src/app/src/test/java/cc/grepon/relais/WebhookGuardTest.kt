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

import cc.grepon.relais.batch.WebhookGuard
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [WebhookGuard] — the SSRF guard. The resolver is injected with literal IPs
 * (InetAddress parses these without DNS), so the classification is tested deterministically offline.
 */
class WebhookGuardTest {

  private fun resolvesTo(vararg ips: String): (String) -> Array<InetAddress> =
    { ips.map { InetAddress.getByName(it) }.toTypedArray() }

  private fun assertBlocked(v: WebhookGuard.Verdict) =
    assertTrue("expected Blocked, was $v", v is WebhookGuard.Verdict.Blocked)

  /** Allowed AND the vetted address is handed back for pinning (the TOCTOU defence depends on this). */
  private fun assertAllowedPinning(v: WebhookGuard.Verdict, expectedIp: String) {
    assertTrue("expected Allowed, was $v", v is WebhookGuard.Verdict.Allowed)
    val addrs = (v as WebhookGuard.Verdict.Allowed).addresses
    assertTrue("expected a pinned address", addrs.isNotEmpty())
    assertEquals(expectedIp, addrs.first().hostAddress)
  }

  @Test fun `public https destination is allowed and pins the resolved address`() {
    assertAllowedPinning(
      WebhookGuard.check("https://hooks.example.com/x", resolve = resolvesTo("93.184.216.34")),
      "93.184.216.34",
    )
  }

  @Test fun `http is blocked unless allowlisted`() {
    assertBlocked(WebhookGuard.check("http://hooks.example.com/x", resolve = resolvesTo("93.184.216.34")))
  }

  @Test fun `loopback private link-local wildcard multicast are blocked`() {
    val cases = mapOf(
      "https://a/x" to "127.0.0.1",
      "https://b/x" to "10.0.0.5",
      "https://c/x" to "172.16.9.9",
      "https://d/x" to "192.168.1.10",
      "https://e/x" to "169.254.169.254", // cloud metadata endpoint
      "https://f/x" to "0.0.0.0",
      "https://g/x" to "224.0.0.1",
      "https://h/x" to "::1",
      "https://i/x" to "fc00::1",
      "https://j/x" to "fd12:3456::1",
    )
    for ((url, ip) in cases) assertBlocked(WebhookGuard.check(url, resolve = resolvesTo(ip)))
  }

  @Test fun `DNS rebinding is caught — any private resolved address blocks`() {
    assertBlocked(WebhookGuard.check("https://evil.example.com/x", resolve = resolvesTo("93.184.216.34", "10.0.0.1")))
  }

  @Test fun `allowlisted host bypasses https and private checks but still pins the address`() {
    assertAllowedPinning(
      WebhookGuard.check("http://internal.lan/hook", allowlist = setOf("internal.lan"), resolve = resolvesTo("10.0.0.7")),
      "10.0.0.7",
    )
  }

  @Test fun `malformed unresolvable and host-less URLs are blocked`() {
    assertBlocked(WebhookGuard.check("not a url"))
    assertBlocked(WebhookGuard.check("https:///nohost"))
    assertBlocked(WebhookGuard.check("https://x/x", resolve = { throw java.net.UnknownHostException("x") }))
    assertBlocked(WebhookGuard.check("https://x/x", resolve = { emptyArray() }))
  }

  @Test fun `classify pins each category`() {
    assertEquals("loopback", WebhookGuard.classify(InetAddress.getByName("127.0.0.1")))
    assertEquals("private", WebhookGuard.classify(InetAddress.getByName("192.168.0.1")))
    assertEquals("link-local", WebhookGuard.classify(InetAddress.getByName("169.254.1.1")))
    assertEquals(null, WebhookGuard.classify(InetAddress.getByName("8.8.8.8")))
  }
}
