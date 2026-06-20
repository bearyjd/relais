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

package cc.grepon.relais.triage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import cc.grepon.relais.Amber
import cc.grepon.relais.Charcoal
import cc.grepon.relais.Line
import cc.grepon.relais.Muted
import cc.grepon.relais.Panel
import cc.grepon.relais.Paper
import cc.grepon.relais.StopRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A launchable app the user can add to the triage allowlist. */
private data class AppEntry(val pkg: String, val label: String)

/**
 * Control panel for on-device notification triage (Relais feature #7). Strictly opt-in: triage stays
 * off until the user enables it (one-time consent), grants system Notification Access, and explicitly
 * allowlists apps (default-deny). Mirrors the [cc.grepon.relais.RelaisControlActivity] aesthetic.
 */
class TriageControlActivity : ComponentActivity() {
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
            error = StopRed,
          )
      ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Charcoal) { TriageScreen() }
      }
    }
  }
}

@Composable
private fun TriageScreen() {
  val ctx = LocalContext.current

  var enabled by remember { mutableStateOf(TriageConfig.enabled(ctx)) }
  var urgent by remember { mutableStateOf(TriageConfig.urgentEnabled(ctx)) }
  var interval by remember { mutableStateOf(TriageConfig.intervalMinutes(ctx).toInt()) }
  var allow by remember { mutableStateOf(TriageConfig.allowlist(ctx)) }
  var accessGranted by remember { mutableStateOf(isAccessGranted(ctx)) }
  var showConsent by remember { mutableStateOf(false) }
  var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

  // Load the launcher-app list off the main thread (loadLabel touches resources).
  LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadLaunchableApps(ctx) } }
  // Notification Access is granted in system Settings, so poll for the return-from-settings change.
  LaunchedEffect(Unit) {
    while (true) {
      accessGranted = isAccessGranted(ctx)
      delay(1000)
    }
  }

  val notifPerm =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

  fun enableTriage() {
    TriageConfig.setEnabled(ctx, true)
    enabled = true
    TriageNotifications.ensureChannels(ctx)
    TriageDigestWorker.ensureScheduled(ctx)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (!isAccessGranted(ctx)) openNotificationAccessSettings(ctx)
  }

  fun disableTriage() {
    TriageConfig.setEnabled(ctx, false)
    enabled = false
    TriageDigestWorker.cancel(ctx) // cancels both the periodic schedule and any "Triage now" run
    TriageUrgentWorker.cancel(ctx)
    NotificationTriageBuffer.clear()
    TriageRateLimiter.reset()
  }

  Column(
    modifier =
      Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)
        .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "NOTIFICATION TRIAGE",
        color = Amber,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 3.sp,
      )
      Spacer(Modifier.weight(1f))
      Text(
        if (enabled) "ON" else "OFF",
        color = if (enabled) Amber else Muted,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 2.sp,
      )
    }
    Text(
      "on-device only · content never leaves this phone",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )

    Divider()
    ActionLink(if (enabled) "DISABLE TRIAGE" else "ENABLE TRIAGE") {
      when {
        enabled -> disableTriage()
        !TriageConfig.consented(ctx) -> showConsent = true
        else -> enableTriage()
      }
    }

    Readout("NOTIFICATION ACCESS", if (accessGranted) "granted" else "not granted")
    if (!accessGranted) {
      ActionLink("GRANT NOTIFICATION ACCESS ›") { openNotificationAccessSettings(ctx) }
    }

    if (enabled) {
      Divider()
      Readout("URGENT SURFACING", if (urgent) "on" else "off")
      ActionLink(if (urgent) "DISABLE URGENT SURFACING" else "ENABLE URGENT SURFACING") {
        val next = !urgent
        TriageConfig.setUrgentEnabled(ctx, next)
        urgent = next
      }

      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("DIGEST EVERY", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Stepper("–") {
          val next = (interval - 15).coerceAtLeast(TriageConfig.MIN_INTERVAL_MIN)
          if (next != interval) {
            interval = next
            TriageConfig.setIntervalMinutes(ctx, next)
            TriageDigestWorker.reschedule(ctx)
          }
        }
        Text(
          "$interval min",
          color = Paper,
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
          modifier = Modifier.padding(horizontal = 12.dp),
        )
        Stepper("+") {
          val next = (interval + 15).coerceAtMost(TriageConfig.MAX_INTERVAL_MIN)
          if (next != interval) {
            interval = next
            TriageConfig.setIntervalMinutes(ctx, next)
            TriageDigestWorker.reschedule(ctx)
          }
        }
      }

      if (accessGranted) {
        ActionLink("TRIAGE NOW ›") { TriageDigestWorker.triggerNow(ctx) }
      }
    }

    Divider()
    Text(
      "ALLOWLIST",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
      letterSpacing = 1.sp,
    )
    Text(
      "Only allowlisted apps are ever read. Default: none.",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )
    if (apps.isEmpty()) {
      Text("loading apps…", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    } else {
      apps.forEach { app ->
        val checked = app.pkg in allow
        AppToggleRow(label = app.label, pkg = app.pkg, checked = checked) {
          val next = if (checked) allow - app.pkg else allow + app.pkg
          allow = next
          TriageConfig.setAllowlist(ctx, next)
        }
      }
      // Allowlisted packages with no launcher entry (uninstalled, or never had one) would otherwise
      // be invisible and un-removable — surface them so the set can always be pruned.
      val listed = apps.map { it.pkg }.toSet()
      allow.filter { it !in listed }.sorted().forEach { pkg ->
        AppToggleRow(label = pkg, pkg = "allowlisted · not in launcher", checked = true) {
          val next = allow - pkg
          allow = next
          TriageConfig.setAllowlist(ctx, next)
        }
      }
    }

    Divider()
    Text(
      "To stop completely, revoke Notification Access in Android Settings — that is the authoritative " +
        "kill switch. Disabling here also cancels the digest and clears buffered notifications.",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )
  }

  if (showConsent) {
    AlertDialog(
      onDismissRequest = { showConsent = false },
      title = {
        Text("Enable notification triage?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
      },
      text = {
        Text(
          "Relais will read notifications from the apps you allowlist and run them through the " +
            "on-device model to summarize them and flag urgent ones. The content never leaves this " +
            "device — no network, no LAN endpoint, no storage; it is held in memory only until a " +
            "digest is produced. You will grant system Notification Access next, and can revoke it " +
            "at any time to stop completely.",
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showConsent = false
            TriageConfig.setConsented(ctx, true)
            TriageConfig.setEnabled(ctx, true)
            enabled = true
            TriageNotifications.ensureChannels(ctx)
            TriageDigestWorker.ensureScheduled(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!isAccessGranted(ctx)) openNotificationAccessSettings(ctx)
          },
          colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Charcoal),
        ) {
          Text("I UNDERSTAND", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        OutlinedButton(
          onClick = { showConsent = false },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = Muted),
        ) {
          Text("CANCEL", fontFamily = FontFamily.Monospace)
        }
      },
      containerColor = Panel,
      titleContentColor = Paper,
      textContentColor = Paper,
    )
  }
}

@Composable
private fun Readout(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp)
    Spacer(Modifier.width(16.dp))
    Text(value, color = Paper, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
  }
}

@Composable
private fun Divider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
private fun ActionLink(label: String, onClick: () -> Unit) {
  Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(vertical = 4.dp)) {
    Text(label, color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
  }
}

@Composable
private fun Stepper(symbol: String, onClick: () -> Unit) {
  Box(
    Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 2.dp)
  ) {
    Text(symbol, color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
  }
}

@Composable
private fun AppToggleRow(label: String, pkg: String, checked: Boolean, onToggle: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onToggle() }.padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      if (checked) "[x]" else "[ ]",
      color = if (checked) Amber else Muted,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 13.sp,
    )
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(label, color = Paper, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
      Text(pkg, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
  }
}

private fun isAccessGranted(ctx: Context): Boolean =
  NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

private fun openNotificationAccessSettings(ctx: Context) {
  runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
}

private fun loadLaunchableApps(ctx: Context): List<AppEntry> {
  val pm = ctx.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return runCatching {
    pm.queryIntentActivities(intent, 0)
      .mapNotNull { ri ->
        val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
        if (pkg == ctx.packageName) return@mapNotNull null
        AppEntry(pkg, ri.loadLabel(pm).toString())
      }
      .distinctBy { it.pkg }
      .sortedBy { it.label.lowercase() }
  }.getOrDefault(emptyList())
}
