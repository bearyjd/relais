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

package cc.grepon.relais

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the redesigned control-panel UI files against off-palette colors (DESIGN.md is law — see
 * CLAUDE.md). Every `0x..` color literal in these files must be one of the seven tokens defined in
 * RelaisPalette.kt; anything else is a new, undocumented color slipping into the "amber is the only
 * accent" system.
 *
 * Scans source text directly (not a Robolectric/Compose render) — cheap, and it catches a stray
 * literal even in a branch no test happens to render.
 */
class RelaisControlPanelPaletteGuardTest {

  private val allowedHexLiterals = setOf(
    "0xFFFFB000", // Amber
    "0xFF0B0B0D", // Charcoal
    "0xFF16171A", // Panel
    "0xFF2A2B30", // Line
    "0xFFEDEAE3", // Paper
    "0xFF8A8780", // Muted
    "0xFFFF5247", // StopRed
  )

  private val hexLiteralRegex = Regex("0x[0-9A-Fa-f]{6,8}")

  // Compose's built-in Color companion members (Color.Red, Color.White, Color.Black, …) are a
  // second way an off-palette color can slip in without ever writing a hex literal. None of them
  // are needed today — RelaisPalette.kt's seven tokens cover every color this UI uses — so the
  // allowlist is empty; a real future need (e.g. Color.Transparent) should extend it deliberately,
  // not silently pass because the hex-literal scan alone didn't catch it (review L2).
  private val allowedNamedColorMembers = emptySet<String>()

  // Negative lookbehind excludes any identifier merely ENDING in "Color" (e.g. `statusColor.copy(`,
  // `animatedStatusColor.copy(`) — only a bare `Color.Xxx` (the Compose companion object) matches.
  private val namedColorRegex = Regex("""(?<![A-Za-z0-9_])Color\.([A-Za-z][A-Za-z0-9]*)""")

  /**
   * Finds `RelaisPalette.kt`'s directory by walking up from this test class's own source-relative
   * location — robust to whichever directory Gradle's Test task happens to run `user.dir` from.
   */
  private fun packageDir(): File {
    val candidates = listOf(
      File("src/main/java/cc/grepon/relais"),
      File("app/src/main/java/cc/grepon/relais"),
      File("Android/src/app/src/main/java/cc/grepon/relais"),
    )
    return candidates.firstOrNull { File(it, "RelaisPalette.kt").isFile }
      ?: error(
        "Could not locate cc.grepon.relais source dir from user.dir=${File(".").absolutePath}; " +
          "tried $candidates"
      )
  }

  private fun assertNoOffPaletteHex(fileName: String) {
    val file = File(packageDir(), fileName)
    assertTrue("$fileName must exist for the palette guard to scan it", file.isFile)
    val text = file.readText()
    val found = hexLiteralRegex.findAll(text).map { it.value.uppercase() }.toSet()
    val offPalette = found - allowedHexLiterals.map { it.uppercase() }.toSet()
    assertTrue(
      "$fileName contains off-palette hex color(s) not in RelaisPalette.kt: $offPalette " +
        "(DESIGN.md is law — amber #FFB000 is the only accent; no new colors)",
      offPalette.isEmpty(),
    )
  }

  /**
   * A second way an off-palette color can slip in without ever writing a hex literal: reaching for
   * Compose's built-in `Color.Red`/`Color.White`/`Color.Black`/… companion members directly instead
   * of one of RelaisPalette.kt's seven named tokens (review L2). `Color(` constructor calls and
   * bare `Color` type references (e.g. a `valueColor: Color` parameter) are unaffected — only a
   * dotted member access on `Color` is flagged.
   */
  private fun assertNoOffPaletteNamedColors(fileName: String) {
    val file = File(packageDir(), fileName)
    assertTrue("$fileName must exist for the palette guard to scan it", file.isFile)
    val text = file.readText()
    val found = namedColorRegex.findAll(text).map { it.groupValues[1] }.toSet()
    val offPalette = found - allowedNamedColorMembers
    assertTrue(
      "$fileName references off-palette Color.* member(s) not allowlisted: $offPalette " +
        "(use one of RelaisPalette.kt's tokens instead — DESIGN.md is law, no new colors)",
      offPalette.isEmpty(),
    )
  }

  @Test
  fun `RelaisControlActivity uses only palette colors`() {
    assertNoOffPaletteHex("RelaisControlActivity.kt")
    assertNoOffPaletteNamedColors("RelaisControlActivity.kt")
  }

  @Test
  fun `RelaisConfigureActivity uses only palette colors`() {
    assertNoOffPaletteHex("RelaisConfigureActivity.kt")
    assertNoOffPaletteNamedColors("RelaisConfigureActivity.kt")
  }

  @Test
  fun `RelaisControlPanelState uses no hardcoded color literals at all`() {
    // The pure state file is Compose/Color-free by design — it must never import androidx.compose.
    val file = File(packageDir(), "RelaisControlPanelState.kt")
    assertTrue("RelaisControlPanelState.kt must exist", file.isFile)
    val text = file.readText()
    assertTrue(
      "RelaisControlPanelState.kt must stay pure Kotlin (no androidx.compose imports) so it's " +
        "JVM-testable with no Android/Robolectric dependency",
      !text.contains("androidx.compose"),
    )
    assertTrue("RelaisControlPanelState.kt must not contain hex color literals", hexLiteralRegex.find(text) == null)
  }
}
