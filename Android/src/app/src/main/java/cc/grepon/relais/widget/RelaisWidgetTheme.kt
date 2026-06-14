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

package cc.grepon.relais.widget

import androidx.glance.color.ColorProviders
import androidx.glance.color.colorProviders
import androidx.glance.unit.ColorProvider
import cc.grepon.relais.Amber
import cc.grepon.relais.Charcoal
import cc.grepon.relais.Line
import cc.grepon.relais.Muted
import cc.grepon.relais.Paper
import cc.grepon.relais.Panel
import cc.grepon.relais.StopRed

/**
 * Glance [ColorProviders] for the home-screen widget (#3), mapping the canonical [RelaisPalette]
 * tokens (DESIGN.md: amber signal-relay on near-black) onto the Material color slots Glance themes by.
 * Fixed (not day/night) — the node UI is intentionally a single dark surface, per DESIGN.md ("do not
 * default to dark mode automatically" does not apply: this is the brand's deliberate direction).
 *
 *  - background / surface → Charcoal / Panel (near-black surfaces)
 *  - primary → Amber (the signal-relay accent, used for active buttons)
 *  - onBackground / onSurface → Paper (warm off-white text)
 *  - error → StopRed (reserved exclusively for the stop/error signal, per DESIGN.md)
 *  - outline → Line (hairline dividers); onSurfaceVariant → Muted (secondary text)
 */
object RelaisWidgetTheme {
  val colors: ColorProviders = colorProviders(
    primary = ColorProvider(Amber),
    onPrimary = ColorProvider(Charcoal),
    primaryContainer = ColorProvider(Panel),
    onPrimaryContainer = ColorProvider(Amber),
    secondary = ColorProvider(Muted),
    onSecondary = ColorProvider(Charcoal),
    secondaryContainer = ColorProvider(Panel),
    onSecondaryContainer = ColorProvider(Paper),
    tertiary = ColorProvider(Amber),
    onTertiary = ColorProvider(Charcoal),
    tertiaryContainer = ColorProvider(Panel),
    onTertiaryContainer = ColorProvider(Paper),
    error = ColorProvider(StopRed),
    onError = ColorProvider(Charcoal),
    errorContainer = ColorProvider(Panel),
    onErrorContainer = ColorProvider(StopRed),
    background = ColorProvider(Charcoal),
    onBackground = ColorProvider(Paper),
    surface = ColorProvider(Panel),
    onSurface = ColorProvider(Paper),
    surfaceVariant = ColorProvider(Panel),
    onSurfaceVariant = ColorProvider(Muted),
    outline = ColorProvider(Line),
    inverseOnSurface = ColorProvider(Charcoal),
    inverseSurface = ColorProvider(Paper),
    inversePrimary = ColorProvider(Amber),
    widgetBackground = ColorProvider(Charcoal),
  )
}
