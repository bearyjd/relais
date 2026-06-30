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

package cc.grepon.relais.imagegen

import android.content.Context

/**
 * degoogled-flavor image-gen registration (flavor twin of the full version). NO-OP by design: the
 * degoogled build excludes llmedge/Vulkan, so no [RelaisImageGenerator] is registered and the provider
 * stays null → `POST /v1/images/generations` returns an honest 501. Keeping the same FQN as the full
 * twin lets [cc.grepon.relais.RelaisNodeService] call it unconditionally.
 */
object ImageGenRegistration {
  fun register(context: Context) {
    // Intentionally empty — see the class KDoc.
  }
}
