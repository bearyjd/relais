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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenBackendPolicyTest {

  @Test
  fun `forces CPU on Pixel 10 (PowerVR G5 Vulkan deadlock, issue 69)`() {
    assertTrue(imageGenForcesCpuBackend(isPixel10 = true))
  }

  @Test
  fun `keeps Vulkan on non-Pixel-10 devices (Mali G3 G4)`() {
    assertFalse(imageGenForcesCpuBackend(isPixel10 = false))
  }
}
