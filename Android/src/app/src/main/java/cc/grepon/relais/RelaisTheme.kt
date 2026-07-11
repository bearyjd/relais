/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The single amber-on-charcoal (DESIGN.md) theme wrapper for every node surface.
 * Replaces the identical inline block previously duplicated in RelaisControlActivity,
 * RelaisChatActivity, and RelaisConfigureActivity.
 */
@Composable
fun RelaisTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = Amber,
      onPrimary = Charcoal,
      background = Charcoal,
      onBackground = Paper,
      surface = Panel,
      onSurface = Paper,
      error = StopRed,
    ),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = Charcoal) { content() }
  }
}
