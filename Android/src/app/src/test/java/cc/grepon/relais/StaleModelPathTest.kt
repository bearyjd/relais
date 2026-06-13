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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [RelaisModelProvisioner.shouldPersistPath] — issue #11.
 *
 * Guards against the race where ensureModel resolves model A, the operator changes the node to
 * model B mid-download (which clears KEY_MODEL_PATH), and the still-running ensureModel then
 * re-writes the OLD path — resurrecting a stale path and serving the wrong model on next boot.
 *
 * No Context / Robolectric needed: shouldPersistPath is a pure function.
 */
class StaleModelPathTest {

  @Test
  fun `shouldPersistPath returns true when provisionedForId is null (no drift info)`() {
    assertTrue(
      "null provisionedForId must permit persist (preserves legacy callers)",
      RelaisModelProvisioner.shouldPersistPath(null, "litert-community/gemma-4-E4B-it-litert-lm"),
    )
  }

  @Test
  fun `shouldPersistPath returns true when provisionedForId matches currentId (no drift)`() {
    assertTrue(
      "identical ids must permit persist (happy path, no drift)",
      RelaisModelProvisioner.shouldPersistPath(
        "litert-community/gemma-4-E4B-it-litert-lm",
        "litert-community/gemma-4-E4B-it-litert-lm",
      ),
    )
  }

  @Test
  fun `shouldPersistPath returns false when provisionedForId differs from currentId (drift)`() {
    assertFalse(
      "drifted id must block persist (bug guard — stale path must not be written)",
      RelaisModelProvisioner.shouldPersistPath(
        "litert-community/gemma-4-E4B-it-litert-lm",
        "litert-community/gemma3-1b-it-int4-litert-lm",
      ),
    )
  }
}
