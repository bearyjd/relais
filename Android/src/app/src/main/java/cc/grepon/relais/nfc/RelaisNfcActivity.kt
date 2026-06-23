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

package cc.grepon.relais.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.core.RelaisInference
import cc.grepon.relais.share.RelaisShareService
import cc.grepon.relais.templates.WorkflowRegistry

/**
 * Exported, no-UI trampoline for an NFC workflow tap (#15). A tag encodes
 * `com.ventouxlabs.relais://workflow/<templateId>` (+ optional `?q=`). On tap this resolves the #12 template
 * and runs it on the resident model, posting a VISIBILITY_PRIVATE result notification.
 *
 * Trust model (operator decision): **opt-in** — inert unless `RelaisConfig.nfcEnabled`. When enabled,
 * any Relais-scheme tag triggers (physical access to the unlocked phone is the boundary). The tag's
 * inline prompt is **untrusted** and runs through plain inference only (no node-tools, no LAN egress).
 * Like the share/widget paths, it never cold-starts the node (gates on `RelaisInference.isReady`).
 */
class RelaisNfcActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handle(intent)
    finish()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handle(intent)
    finish()
  }

  private fun handle(intent: Intent?) {
    val ctx = applicationContext
    if (!RelaisConfig.nfcEnabled(ctx)) {
      Log.i(TAG, "NFC disabled; tap ignored")
      return
    }
    val uri = ndefUri(intent) ?: run { Log.i(TAG, "no NDEF uri in tap"); return }
    val tap = NfcWorkflowParser.parse(uri) ?: run { Log.i(TAG, "not a Relais workflow uri"); return }
    val template =
      WorkflowRegistry.resolve(ctx, tap.templateId)
        ?: run { Log.i(TAG, "unknown workflow id; tap ignored"); return }
    if (!RelaisInference.isReady()) {
      Log.i(TAG, "node not ready; tap ignored (cold-start guard)")
      return
    }
    val prompt = tap.prompt ?: DEFAULT_NFC_PROMPT
    runCatching {
      ContextCompat.startForegroundService(ctx, RelaisShareService.promptIntent(ctx, prompt, template.system))
    }
      .onFailure { Log.w(TAG, "could not start inference service", it) }
  }

  /** The matched data URI is normally the intent data; fall back to the raw NDEF records. */
  private fun ndefUri(intent: Intent?): String? {
    intent ?: return null
    intent.dataString?.takeIf { it.isNotBlank() }?.let { return it }
    val raw =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
      } else {
        @Suppress("DEPRECATION") intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
      }
    raw?.forEach { parcel ->
      (parcel as? NdefMessage)?.records?.forEach { record ->
        runCatching { record.toUri() }.getOrNull()?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
      }
    }
    return null
  }

  companion object {
    private const val TAG = "RelaisNfc"

    /** Used when a tag carries only a template id (no inline `?q=` prompt). */
    const val DEFAULT_NFC_PROMPT = "Give me a one-line status check."
  }
}
