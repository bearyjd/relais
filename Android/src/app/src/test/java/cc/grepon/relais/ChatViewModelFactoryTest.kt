/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Relais.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ChatViewModel] must be constructible by the DEFAULT factory, because that is what
 * `ChatScreen`'s `viewModel()` call uses.
 *
 * ### Why this test exists
 * `AndroidViewModelFactory` reflects for a constructor taking exactly `(Application)`. Kotlin
 * default arguments do **not** emit that overload on the JVM unless the constructor is annotated
 * `@JvmOverloads`. #211/#214 added a second parameter (`speechDispatcher`, injectable so the speech
 * seam tests can use a test dispatcher) without it — which silently removed the `(Application)`
 * constructor and made every entry into the chat screen die with:
 *
 * ```
 * java.lang.RuntimeException: Cannot create an instance of class cc.grepon.relais.ChatViewModel
 *   at androidx.lifecycle.ViewModelProvider$AndroidViewModelFactory.create
 * ```
 *
 * In-app chat was therefore **completely broken** and shipped that way, because nothing exercised
 * this path: the JVM suite constructs `ChatViewModel` directly with explicit arguments, and the
 * Compose probes drive `ChatMessageList` / `SendStopButton` in isolation without a ViewModel at all.
 * Every test was green while the feature was dead.
 *
 * This asserts the production construction path specifically — do not "simplify" it to
 * `ChatViewModel(app)`, which compiles via default arguments and would pass even while the JVM
 * overload the framework needs is absent. The reflection is the point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatViewModelFactoryTest {

  @Test
  fun `the default AndroidViewModelFactory can construct ChatViewModel`() {
    val app = ApplicationProvider.getApplicationContext<Application>()

    // Exactly what viewModel() does inside ChatScreen.
    val provider = ViewModelProvider(ViewModelStore(), ViewModelProvider.AndroidViewModelFactory.getInstance(app))

    assertNotNull(
      "ChatScreen's viewModel() cannot construct ChatViewModel — the (Application) constructor " +
        "is gone. Adding a parameter without @JvmOverloads removes it and kills in-app chat.",
      provider[ChatViewModel::class.java],
    )
  }

  /**
   * The framework path above is what actually breaks, but pin the reflective shape directly too so
   * the failure names the cause rather than surfacing as a factory error one layer up.
   */
  @Test
  fun `ChatViewModel exposes a single-Application JVM constructor`() {
    assertNotNull(
      "@JvmOverloads is missing from ChatViewModel's constructor",
      ChatViewModel::class.java.getConstructor(Application::class.java),
    )
  }
}
