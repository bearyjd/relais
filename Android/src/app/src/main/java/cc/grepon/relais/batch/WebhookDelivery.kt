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

package cc.grepon.relais.batch

import android.content.Context
import android.util.Log
import cc.grepon.relais.RelaisConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Delivers a batch result to a webhook URL: re-runs the SSRF [WebhookGuard] (the destination is
 * re-checked at delivery, not just at submit — TOCTOU), signs the body with HMAC-SHA256
 * ([WebhookSigner], `X-Relais-Signature`), and POSTs it. Redirects are NOT followed (a 3xx could point
 * at a private IP, bypassing the guard). Best-effort: returns whether the receiver answered 2xx.
 */
object WebhookDelivery {

  private const val TAG = "RelaisWebhook"

  fun deliver(context: Context, url: String, payload: String): Boolean {
    when (val verdict = WebhookGuard.check(url, RelaisConfig.webhookAllowlist(context))) {
      is WebhookGuard.Verdict.Blocked -> {
        Log.w(TAG, "webhook delivery blocked: ${verdict.reason}")
        return false
      }
      WebhookGuard.Verdict.Allowed -> Unit
    }
    val signature = WebhookSigner.header(payload, RelaisConfig.webhookHmacSecret(context))
    return try {
      val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = false // a 3xx could redirect to a private IP — don't follow it
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty(WebhookSigner.HEADER, signature)
      }
      conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
      val code = conn.responseCode
      conn.disconnect()
      (code in 200..299).also { if (!it) Log.w(TAG, "webhook receiver returned $code") }
    } catch (e: Exception) {
      Log.w(TAG, "webhook POST failed: ${e.message}")
      false
    }
  }
}
