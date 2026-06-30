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
 * FULL-flavor image-gen registration (flavor twin; the degoogled copy is a no-op). Called once from
 * [cc.grepon.relais.RelaisNodeService] at node init — mirrors the embedder's `register()`. Registering
 * the [SdcppImageGenerator] is cheap (no download/load): the route still gates on `isAvailable`
 * (Vulkan + model provisioned) and triggers provisioning on demand via the 503 branch.
 */
object ImageGenRegistration {
  fun register(context: Context) {
    RelaisImageGeneratorProvider.register(SdcppImageGenerator)
  }
}
