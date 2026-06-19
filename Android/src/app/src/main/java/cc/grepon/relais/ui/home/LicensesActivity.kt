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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FOSS third-party-license screen (replaces the GMS `play-services-oss-licenses` viewer). Parses the
 * real dependency graph from `R.raw.aboutlibraries` (generated at build time by the AboutLibraries
 * Gradle plugin) via the pure-Kotlin `aboutlibraries-core`, and renders it with a HAND-ROLLED Compose
 * list. We deliberately do NOT use `aboutlibraries-compose-m3`: its prebuilt UI is compiled against an
 * older Compose and calls a `FlowRow` overload absent from this project's Compose BOM → a runtime
 * NoSuchMethodError (and the 14.x line that would fix it needs compileSdk 36). Rendering it ourselves
 * is GMS-free, works in BOTH flavors, and styles per DESIGN.md. Internal-only (`exported="false"`).
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
        Surface(modifier = Modifier.fillMaxSize(), color = Charcoal) {
          LicensesScreen(onBack = ::finish)
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  // Parse off the main thread (a few hundred entries). core only — no Compose dependency here.
  val libraries by
    produceState(initialValue = emptyList<Library>(), context) {
      value =
        withContext(Dispatchers.IO) {
          Libs.Builder().withJson(context, R.raw.aboutlibraries).build().libraries.sortedBy {
            it.name.lowercase()
          }
        }
    }
  var selected by remember { mutableStateOf<Library?>(null) }

  // systemBarsPadding so the TopAppBar clears the status bar (targetSdk 35 edge-to-edge; DESIGN.md).
  Scaffold(
    modifier = Modifier.systemBarsPadding(),
    containerColor = Charcoal,
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
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Amber)
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(containerColor = Charcoal, titleContentColor = Amber),
      )
    },
  ) { innerPadding ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      items(libraries, key = { it.uniqueId }) { lib ->
        LibraryRow(lib) { selected = lib }
        HorizontalDivider(color = Line)
      }
    }
  }

  selected?.let { lib -> LicenseDialog(lib, onDismiss = { selected = null }) }
}

@Composable
private fun LibraryRow(library: Library, onClick: () -> Unit) {
  Column(
    modifier =
      Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)
  ) {
    Text(library.name, color = Paper, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    val subtitle = buildString {
      library.artifactVersion?.let { append(it) }
      val licenses = library.licenses.joinToString { it.name }
      if (licenses.isNotBlank()) {
        if (isNotEmpty()) append("  •  ")
        append(licenses)
      }
    }
    if (subtitle.isNotEmpty()) {
      Text(subtitle, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
  }
}

@Composable
private fun LicenseDialog(library: Library, onDismiss: () -> Unit) {
  val licenseText =
    library.licenses.firstOrNull()?.licenseContent?.takeIf { it.isNotBlank() }
      ?: library.licenses
        .joinToString("\n\n") { lic ->
          buildString {
            append(lic.name)
            lic.url?.let {
              append("\n")
              append(it)
            }
          }
        }
        .ifBlank { "No license text available." }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Panel,
    title = { Text(library.name, color = Amber, fontWeight = FontWeight.Bold) },
    text = {
      Text(
        licenseText,
        color = Paper,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.verticalScroll(rememberScrollState()),
      )
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Amber) } },
  )
}
