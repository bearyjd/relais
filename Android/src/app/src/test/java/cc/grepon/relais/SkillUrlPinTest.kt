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

import cc.grepon.relais.customtasks.agentchat.SkillUrlPolicy
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [SkillUrlPolicy.resolvePinned] — the vetting + IP-pinning decision that closes the
 * DNS-rebinding TOCTOU on the skill fetch. The resolver is injected, so this is deterministic + offline.
 */
class SkillUrlPinTest {

  private fun resolvesTo(vararg ips: String): (String) -> Array<InetAddress> =
    { ips.map { InetAddress.getByName(it) }.toTypedArray() }

  @Test fun `public https pins the resolved address with host port and path`() {
    val r = SkillUrlPolicy.resolvePinned(
      "https://example.com/myskill/SKILL.md",
      resolve = resolvesTo("93.184.216.34"),
    )
    assertTrue("$r", r is SkillUrlPolicy.PinResult.Pinned)
    val p = r as SkillUrlPolicy.PinResult.Pinned
    assertEquals(InetAddress.getByName("93.184.216.34"), p.address)
    assertEquals("example.com", p.host)
    assertEquals(443, p.port)
    assertTrue(p.https)
    assertEquals("/myskill/SKILL.md", p.target)
  }

  @Test fun `pins the FIRST vetted address when several resolve`() {
    val r = SkillUrlPolicy.resolvePinned(
      "https://example.com/SKILL.md",
      resolve = resolvesTo("93.184.216.34", "151.101.0.1"),
    )
    val p = r as SkillUrlPolicy.PinResult.Pinned
    assertEquals(InetAddress.getByName("93.184.216.34"), p.address)
  }

  @Test fun `private resolved address is rejected (any-address rebinding defence)`() {
    val r = SkillUrlPolicy.resolvePinned(
      "https://internal.example/SKILL.md",
      resolve = resolvesTo("93.184.216.34", "10.0.0.5"),
    )
    assertEquals(SkillUrlPolicy.PinResult.Rejected(SkillUrlPolicy.BLOCK_MESSAGE), r)
  }

  @Test fun `loopback is rejected`() {
    val r = SkillUrlPolicy.resolvePinned("https://x.example/SKILL.md", resolve = resolvesTo("127.0.0.1"))
    assertEquals(SkillUrlPolicy.PinResult.Rejected(SkillUrlPolicy.BLOCK_MESSAGE), r)
  }

  @Test fun `cloud metadata link-local is rejected`() {
    val r = SkillUrlPolicy.resolvePinned("https://meta.example/SKILL.md", resolve = resolvesTo("169.254.169.254"))
    assertEquals(SkillUrlPolicy.PinResult.Rejected(SkillUrlPolicy.BLOCK_MESSAGE), r)
  }

  @Test fun `http scheme is rejected`() {
    val r = SkillUrlPolicy.resolvePinned("http://example.com/SKILL.md", resolve = resolvesTo("93.184.216.34"))
    assertEquals(SkillUrlPolicy.PinResult.Rejected(SkillUrlPolicy.BLOCK_MESSAGE), r)
  }

  @Test fun `unresolvable host is rejected (fail closed)`() {
    val r = SkillUrlPolicy.resolvePinned("https://nope.example/SKILL.md", resolve = { emptyArray() })
    assertEquals(SkillUrlPolicy.PinResult.Rejected(SkillUrlPolicy.BLOCK_MESSAGE), r)
  }

  @Test fun `explicit port and query are carried into the pin`() {
    val r = SkillUrlPolicy.resolvePinned(
      "https://example.com:8443/s/SKILL.md?ref=main",
      resolve = resolvesTo("93.184.216.34"),
    )
    val p = r as SkillUrlPolicy.PinResult.Pinned
    assertEquals(8443, p.port)
    assertEquals("/s/SKILL.md?ref=main", p.target)
  }

  @Test fun `malformed url is rejected (URI parse fails closed)`() {
    val r = SkillUrlPolicy.resolvePinned("ht!tp://not a url", resolve = resolvesTo("93.184.216.34"))
    assertEquals(SkillUrlPolicy.PinResult.Rejected(SkillUrlPolicy.BLOCK_MESSAGE), r)
  }

  @Test fun `validate stays consistent with resolvePinned`() {
    assertEquals(null, SkillUrlPolicy.validate("https://example.com/SKILL.md", resolve = resolvesTo("93.184.216.34")))
    assertEquals(
      SkillUrlPolicy.BLOCK_MESSAGE,
      SkillUrlPolicy.validate("https://example.com/SKILL.md", resolve = resolvesTo("10.0.0.1")),
    )
  }
}
