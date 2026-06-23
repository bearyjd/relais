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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [SkillUrlPolicy] — the SSRF guard on "add skill from URL". The resolver is
 * injected with literal IPs (no DNS), so classification is deterministic + offline. Mirrors
 * `WebhookGuardTest` (both share [cc.grepon.relais.batch.WebhookGuard]).
 */
class SkillUrlPolicyTest {

  private fun resolvesTo(vararg ips: String): (String) -> Array<InetAddress> =
    { ips.map { InetAddress.getByName(it) }.toTypedArray() }

  @Test fun `public https skill source is allowed`() {
    assertNull(
      SkillUrlPolicy.validate(
        "https://example.com/myskill/SKILL.md",
        resolve = resolvesTo("93.184.216.34"),
      )
    )
  }

  @Test fun `http skill source is blocked`() {
    assertNotNull(
      SkillUrlPolicy.validate("http://example.com/s/SKILL.md", resolve = resolvesTo("93.184.216.34"))
    )
  }

  @Test fun `loopback private link-local and cloud-metadata hosts are blocked`() {
    val blockedIps =
      listOf("127.0.0.1", "10.0.0.5", "172.16.1.1", "192.168.1.10", "169.254.169.254", "::1", "fc00::1")
    for (ip in blockedIps) {
      assertNotNull(
        "expected block for skill host resolving to $ip",
        SkillUrlPolicy.validate("https://skill.host/s/SKILL.md", resolve = resolvesTo(ip)),
      )
    }
  }

  @Test fun `DNS rebinding — any private resolved address blocks`() {
    assertNotNull(
      SkillUrlPolicy.validate(
        "https://evil.example.com/s/SKILL.md",
        resolve = resolvesTo("93.184.216.34", "10.0.0.1"),
      )
    )
  }

  @Test fun `file scheme host-less and malformed URLs are blocked`() {
    assertNotNull(SkillUrlPolicy.validate("file:///etc/passwd"))
    assertNotNull(SkillUrlPolicy.validate("https:///nohost"))
    assertNotNull(SkillUrlPolicy.validate("not a url"))
  }
}
