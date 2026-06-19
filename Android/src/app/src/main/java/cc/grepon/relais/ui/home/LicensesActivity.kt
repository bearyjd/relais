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

package cc.grepon.relais.ui.home

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cc.grepon.relais.Amber
import cc.grepon.relais.Charcoal
import cc.grepon.relais.Line
import cc.grepon.relais.Muted
import cc.grepon.relais.Panel
import cc.grepon.relais.Paper
import cc.grepon.relais.R
import cc.grepon.relais.StopRed
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.m3.LibraryDefaults
import com.mikepenz.aboutlibraries.util.withJson

/**
 * FOSS third-party-license screen (replaces the GMS `play-services-oss-licenses` viewer). Reads the
 * real dependency graph from `R.raw.aboutlibraries` (generated at build time by the AboutLibraries
 * Gradle plugin) and renders it with [LibrariesContainer]. Pure FOSS — works identically in BOTH
 * flavors, so the Settings "Third-party libraries" row is always shown.
 *
 * Styled per DESIGN.md (amber on charcoal, monospace, Panel surface) to inherit the Relais look —
 * mirrors `PromptTemplateEditorActivity`. Internal-only: launched solely from the Settings dialog
 * (`android:exported="false"`); up-navigates to MainActivity.
 */
class LicensesActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme(
        colorScheme =
          darkColorScheme(
            primary = Amber,
            onPrimary = Charcoal,
            background = Charcoal,
            onBackground = Paper,
            surface = Panel,
            onSurface = Paper,
            surfaceVariant = Panel,
            onSurfaceVariant = Muted,
            outline = Line,
            error = StopRed,
          )
      ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Charcoal) { LicensesScreen() }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesScreen() {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            "OPEN-SOURCE LICENSES",
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 3.sp,
          )
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = Charcoal,
            titleContentColor = Amber,
          ),
      )
    },
    containerColor = Charcoal,
  ) { innerPadding ->
    // librariesBlock loads R.raw.aboutlibraries off the main thread (Dispatchers.IO). The resource is
    // plugin-generated at build time, so it's always present in a built APK.
    LibrariesContainer(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      librariesBlock = { ctx -> Libs.Builder().withJson(ctx, R.raw.aboutlibraries).build() },
      colors =
        LibraryDefaults.libraryColors(
          backgroundColor = Charcoal,
          contentColor = Paper,
          badgeBackgroundColor = Panel,
          badgeContentColor = Amber,
          dialogConfirmButtonColor = Amber,
        ),
    )
  }
}
