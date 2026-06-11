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

import androidx.compose.ui.graphics.Color

// Relais brand palette — amber signal relay on near-black. The single source for every node UI
// surface (control panel, model selector). Values are the canonical tokens from DESIGN.md; do not
// change them here without updating DESIGN.md. StopRed is reserved exclusively for the Stop action.
internal val Amber = Color(0xFFFFB000)
internal val Charcoal = Color(0xFF0B0B0D)
internal val Panel = Color(0xFF16171A)
internal val Line = Color(0xFF2A2B30)
internal val Paper = Color(0xFFEDEAE3)
internal val Muted = Color(0xFF8A8780)
internal val StopRed = Color(0xFFFF5247)
