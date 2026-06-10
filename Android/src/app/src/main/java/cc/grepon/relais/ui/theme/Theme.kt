/*
 * Copyright 2025 Google LLC
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

package cc.grepon.relais.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import cc.grepon.relais.proto.Theme

private val lightScheme =
  lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
  )

private val darkScheme =
  darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
  )

@Immutable
data class CustomColors(
  val appTitleGradientColors: List<Color> = listOf(),
  val tabHeaderBgColor: Color = Color.Transparent,
  val taskCardBgColor: Color = Color.Transparent,
  val taskBgColors: List<Color> = listOf(),
  val taskBgGradientColors: List<List<Color>> = listOf(),
  val taskIconColors: List<Color> = listOf(),
  val taskIconShapeBgColor: Color = Color.Transparent,
  val homeBottomGradient: List<Color> = listOf(),
  val userBubbleBgColor: Color = Color.Transparent,
  val agentBubbleBgColor: Color = Color.Transparent,
  val linkColor: Color = Color.Transparent,
  val successColor: Color = Color.Transparent,
  val recordButtonBgColor: Color = Color.Transparent,
  val waveFormBgColor: Color = Color.Transparent,
  val modelInfoIconColor: Color = Color.Transparent,
  val warningContainerColor: Color = Color.Transparent,
  val warningTextColor: Color = Color.Transparent,
  val errorContainerColor: Color = Color.Transparent,
  val errorTextColor: Color = Color.Transparent,
  val newFeatureContainerColor: Color = Color.Transparent,
  val newFeatureTextColor: Color = Color.Transparent,
  val bgStarColor: Color = Color.Transparent,
  val promoBannerBgBrush: Brush = Brush.verticalGradient(listOf(Color.Transparent)),
  val promoBannerIconBgBrush: Brush = Brush.verticalGradient(listOf(Color.Transparent)),
)

val LocalCustomColors = staticCompositionLocalOf { CustomColors() }

val lightCustomColors =
  CustomColors(
    appTitleGradientColors = listOf(Color(0xFFFFC24D), Color(0xFFFFB000)),
    tabHeaderBgColor = Color(0xFFFFB000),
    taskCardBgColor = surfaceContainerLowestLight,
    taskBgColors =
      listOf(
        // red
        Color(0xFFFFF5F5),
        // green
        Color(0xFFF4FBF6),
        // blue
        Color(0xFFF1F6FE),
        // yellow
        Color(0xFFFFFBF0),
      ),
    taskBgGradientColors =
      listOf(
        // amber
        listOf(Color(0xFFFFC24D), Color(0xFFFFB000)),
        // warm orange
        listOf(Color(0xFFFFA85C), Color(0xFFFF8A3D)),
        // gold
        listOf(Color(0xFFF0C25A), Color(0xFFE0A23A)),
        // bronze
        listOf(Color(0xFFE0954A), Color(0xFFC97A2E)),
      ),
    taskIconColors =
      listOf(
        // amber
        Color(0xFFB8760A),
        // warm orange
        Color(0xFFC96A1E),
        // gold
        Color(0xFFA8821F),
        // bronze
        Color(0xFF8A5A22),
      ),
    taskIconShapeBgColor = Color.White,
    homeBottomGradient = listOf(Color(0x00F8F9FF), Color(0xffFFEFC9)),
    agentBubbleBgColor = Color(0xFFF0EFEA),
    userBubbleBgColor = Color(0xFFFFE08A),
    linkColor = Color(0xFF9A6A00),
    successColor = Color(0xff3d860b),
    recordButtonBgColor = Color(0xFFEE675C),
    waveFormBgColor = Color(0xFFaaaaaa),
    modelInfoIconColor = Color(0xFFCCCCCC),
    warningContainerColor = Color(0xfffef7e0),
    warningTextColor = Color(0xffe37400),
    errorContainerColor = Color(0xfffce8e6),
    errorTextColor = Color(0xffd93025),
    newFeatureContainerColor = Color(0xFF3A2A00),
    newFeatureTextColor = Color(0xFFFFB000),
    bgStarColor = Color(0x30FFB000),
    promoBannerBgBrush =
      Brush.linearGradient(
        colorStops =
          arrayOf(
            0.0f to Color(0x42ACB7FF),
            0.6154f to Color(0x422D96FF),
            1.0f to Color(0x423C6BFF),
          ),
        start = Offset(0f, 0f),
        end = Offset(0f, Float.POSITIVE_INFINITY),
      ),
    promoBannerIconBgBrush =
      Brush.linearGradient(
        colorStops =
          arrayOf(
            0.2442f to Color(0x3B446EFF),
            0.4296f to Color(0x3B2E96FF),
            0.6651f to Color(0x3BB1C5FF),
          ),
        start = Offset(0f, 1f),
        end = Offset(1f, 0f),
      ),
  )

val darkCustomColors =
  CustomColors(
    appTitleGradientColors = listOf(Color(0xFFFFC24D), Color(0xFFFFB000)),
    tabHeaderBgColor = Color(0xFFFFB000),
    taskCardBgColor = surfaceContainerHighDark,
    taskBgColors =
      listOf(
        // red
        Color(0xFF181210),
        // green
        Color(0xFF131711),
        // blue
        Color(0xFF191924),
        // yellow
        Color(0xFF1A1813),
      ),
    taskBgGradientColors =
      listOf(
        // amber
        listOf(Color(0xFFFFC24D), Color(0xFFFFB000)),
        // warm orange
        listOf(Color(0xFFFFA85C), Color(0xFFFF8A3D)),
        // gold
        listOf(Color(0xFFF0C25A), Color(0xFFE0A23A)),
        // bronze
        listOf(Color(0xFFE0954A), Color(0xFFC97A2E)),
      ),
    taskIconColors =
      listOf(
        // amber
        Color(0xFFFFB000),
        // warm orange
        Color(0xFFFF8A3D),
        // gold
        Color(0xFFE0A23A),
        // bronze
        Color(0xFFC97A2E),
      ),
    taskIconShapeBgColor = Color(0xFF202124),
    homeBottomGradient = listOf(Color(0x00F8F9FF), Color(0x1AF6AD01)),
    agentBubbleBgColor = Color(0xFF16171A),
    userBubbleBgColor = Color(0xFF1E2024),
    linkColor = Color(0xFFFFB000),
    successColor = Color(0xFFA1CE83),
    recordButtonBgColor = Color(0xFFEE675C),
    waveFormBgColor = Color(0xFFaaaaaa),
    modelInfoIconColor = Color(0xFFCCCCCC),
    warningContainerColor = Color(0xff554c33),
    warningTextColor = Color(0xfffcc934),
    errorContainerColor = Color(0xff523a3b),
    errorTextColor = Color(0xffee675c),
    newFeatureContainerColor = Color(0xFF3A2A00),
    newFeatureTextColor = Color(0xFFFFB000),
    bgStarColor = Color(0x19FFB000),
    promoBannerBgBrush =
      Brush.linearGradient(
        colorStops = arrayOf(0.0f to Color(0x82183570), 0.8077f to Color(0x820A122D)),
        start = Offset(0f, 0f),
        end = Offset(0f, Float.POSITIVE_INFINITY),
      ),
    promoBannerIconBgBrush =
      Brush.linearGradient(
        colorStops =
          arrayOf(
            0.2442f to Color(0x6F0F41F8),
            0.4296f to Color(0x6F1685F8),
            0.6651f to Color(0x6F809EF3),
          ),
        start = Offset(0f, 1f),
        end = Offset(1f, 0f),
      ),
  )

val MaterialTheme.customColors: CustomColors
  @Composable @ReadOnlyComposable get() = LocalCustomColors.current

/**
 * Controls the color of the phone's status bar icons based on whether the app is using a dark
 * theme.
 */
@Composable
fun StatusBarColorController(useDarkTheme: Boolean) {
  val view = LocalView.current
  val currentWindow = (view.context as? Activity)?.window

  if (currentWindow != null) {
    SideEffect {
      WindowCompat.setDecorFitsSystemWindows(currentWindow, false)
      val controller = WindowCompat.getInsetsController(currentWindow, view)
      controller.isAppearanceLightStatusBars = !useDarkTheme // Set to true for light icons
    }
  }
}

@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
  val themeOverride = ThemeSettings.themeOverride
  val darkTheme: Boolean =
    (isSystemInDarkTheme() || themeOverride.value == Theme.THEME_DARK) &&
      themeOverride.value != Theme.THEME_LIGHT
  val view = LocalView.current

  StatusBarColorController(useDarkTheme = darkTheme)

  val colorScheme =
    when {
      darkTheme -> darkScheme
      else -> lightScheme
    }

  val customColorsPalette = if (darkTheme) darkCustomColors else lightCustomColors

  CompositionLocalProvider(LocalCustomColors provides customColorsPalette) {
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
  }

  // Make sure the navigation bar stays transparent on manual theme changes.
  LaunchedEffect(darkTheme) {
    val window = (view.context as Activity).window

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
  }
}
