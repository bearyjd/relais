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

package cc.grepon.relais.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.grepon.relais.Amber
import cc.grepon.relais.Charcoal
import cc.grepon.relais.Line
import cc.grepon.relais.Muted
import cc.grepon.relais.Paper
import cc.grepon.relais.StopRed
import cc.grepon.relais.data.ContentReport
import cc.grepon.relais.data.RelaisDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Operator review of on-device AI-content reports (#258).
 *
 * Play's AI-Generated Content policy requires not just a reporting affordance but that reports
 * "inform content filtering and moderation". With no developer server, that review happens here: the
 * operator reads what was flagged, sees which model produced it, and decides whether to change the
 * model or the system prompt. Nothing is transmitted from this screen.
 *
 * Deliberately NOT gated on `POLICY_OPEN` — this is the surface the Play build most needs.
 */
class ContentReportsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Charcoal) { ContentReportsScreen() }
      }
    }
  }
}

@Composable
private fun ContentReportsScreen() {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  val dao = remember { RelaisDatabase.get(ctx).reportDao() }
  var reports by remember { mutableStateOf<List<ContentReport>>(emptyList()) }

  LaunchedEffect(Unit) { reports = dao.recent(MAX_REVIEWED_REPORTS) }

  Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)) {
    Text(
      text = "REPORTED OUTPUT",
      color = Amber,
      fontFamily = FontFamily.Monospace,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 1.5.sp,
    )
    Spacer(Modifier.height(6.dp))
    Text(
      text =
        "Flagged on this device and never sent anywhere. Use them to decide whether to change the " +
          "model or the system prompt.",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )
    Spacer(Modifier.height(16.dp))

    if (reports.isEmpty()) {
      Text(
        text = "No reports.",
        color = Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
      )
      return@Column
    }

    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      items(reports, key = { it.id }) { report ->
        ReportRow(
          report = report,
          onDismiss = {
            scope.launch {
              dao.delete(report.id)
              reports = dao.recent(MAX_REVIEWED_REPORTS)
            }
          },
        )
      }
    }

    Spacer(Modifier.height(8.dp))
    Text(
      text = "CLEAR ALL",
      color = StopRed,
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      modifier =
        Modifier.semantics { role = Role.Button }
          .clickable {
            scope.launch {
              dao.clear()
              reports = dao.recent(MAX_REVIEWED_REPORTS)
            }
          }
          .padding(vertical = 6.dp),
    )
  }
}

@Composable
private fun ReportRow(report: ContentReport, onDismiss: () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        // Fall back to the stored id when an enum entry has been retired — a row written by an
        // older build must still be readable rather than rendering blank.
        text = labelForReasonId(report.reasonId),
        color = Amber,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = "DISMISS",
        color = Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier =
          Modifier.semantics { role = Role.Button }.clickable(onClick = onDismiss),
      )
    }
    Spacer(Modifier.height(4.dp))
    Text(
      text = "${report.modelId ?: "unknown model"} · ${report.backend ?: "—"} · ${stamp(report.createdAt)}",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )
    Spacer(Modifier.height(6.dp))
    Text(text = report.excerpt, color = Paper, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    report.note?.let {
      Spacer(Modifier.height(6.dp))
      Text(text = "note: $it", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().height(1.dp).background(Line)) {}
  }
}

/** Newest-first review window. Deliberately bounded — this screen reads the whole list into memory. */
private const val MAX_REVIEWED_REPORTS = 200

private fun labelForReasonId(id: String): String =
  ReportReason.entries.firstOrNull { it.id == id }?.label ?: id.uppercase(Locale.US)

private fun stamp(epochMs: Long): String =
  SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
