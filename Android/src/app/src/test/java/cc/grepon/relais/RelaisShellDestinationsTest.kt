/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cc.grepon.relais

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelaisShellDestinationsTest {
  @Test fun startDestination_isDashboard() {
    assertEquals(RelaisRoutes.DASHBOARD, startDestinationRoute())
  }

  @Test fun bottomNav_hasThreeTopLevelItemsInOrder() {
    assertEquals(
      listOf(RelaisRoutes.DASHBOARD, RelaisRoutes.CHAT, RelaisRoutes.MODELS),
      RELAIS_BOTTOM_NAV.map { it.route },
    )
  }

  @Test fun deepLink_globalModelManager_mapsToModels() {
    assertEquals(
      RelaisRoutes.MODELS,
      resolveShellDeepLink("com.ventouxlabs.relais", "global_model_manager"),
    )
  }

  @Test fun deepLink_modelHost_mapsToChat() {
    assertEquals(RelaisRoutes.CHAT, resolveShellDeepLink("com.ventouxlabs.relais", "model"))
  }

  @Test fun deepLink_nullScheme_returnsNull() {
    assertNull(resolveShellDeepLink(null, "global_model_manager"))
  }

  @Test fun deepLink_unknownScheme_returnsNull() {
    assertNull(resolveShellDeepLink("https", "example.com"))
  }
}
