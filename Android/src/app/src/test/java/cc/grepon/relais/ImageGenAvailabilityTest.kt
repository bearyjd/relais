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

import cc.grepon.relais.imagegen.ImageGenAvailability
import cc.grepon.relais.imagegen.imageGenAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM test for the images-endpoint 501/503/200 routing decision (image-gen #16 PR-C). */
class ImageGenAvailabilityTest {

  @Test fun `no generator registered is UNAVAILABLE (501)`() {
    assertEquals(
      ImageGenAvailability.UNAVAILABLE,
      imageGenAvailability(hasGenerator = false, isAvailable = false, canProvision = false),
    )
  }

  @Test fun `registered and available is READY (200)`() {
    assertEquals(
      ImageGenAvailability.READY,
      imageGenAvailability(hasGenerator = true, isAvailable = true, canProvision = false),
    )
  }

  @Test fun `available wins even if canProvision is somehow true`() {
    assertEquals(
      ImageGenAvailability.READY,
      imageGenAvailability(hasGenerator = true, isAvailable = true, canProvision = true),
    )
  }

  @Test fun `unavailable but provisionable is PROVISIONING (503)`() {
    assertEquals(
      ImageGenAvailability.PROVISIONING,
      imageGenAvailability(hasGenerator = true, isAvailable = false, canProvision = true),
    )
  }

  @Test fun `unavailable and cannot provision is UNAVAILABLE (501) - eg no Vulkan`() {
    assertEquals(
      ImageGenAvailability.UNAVAILABLE,
      imageGenAvailability(hasGenerator = true, isAvailable = false, canProvision = false),
    )
  }
}
