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

import cc.grepon.relais.core.RelaisInference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The crux guarantee the tile/widget rely on: the in-process facade is a CONSUMER of an already-up
 * engine — it must NOT cold-start a multi-GB engine from a UI tap. With no resident engine
 * ([RelaisEngine.isReady] == false in a JVM test), `complete` fails fast and EAGERLY (before
 * returning the Flow) so callers can surface "node not running" instead of triggering a provision.
 */
@RunWith(RobolectricTestRunner::class)
class RelaisInferenceTest {

  @Test fun `complete throws NodeNotReady eagerly when engine is not ready`() {
    assertFalse("precondition: no resident engine in a unit test", RelaisEngine.isReady)
    val ctx = RuntimeEnvironment.getApplication()
    assertThrows(RelaisInference.NodeNotReadyException::class.java) {
      // Eager guard: the exception must be thrown by the call itself, not deferred into Flow collection.
      RelaisInference.complete(ctx, "hello")
    }
  }
}
