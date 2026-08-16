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

package cc.grepon.relais.chat

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Opt-in, per-report delivery to the maintainer — the send half of #258 gate 1. [persistContentReport]
 * already wrote the local row; this is a separate, later step the operator chooses, never automatic.
 *
 * Every field here already passed [buildContentReportDraft]'s validation, which mirrors the Worker's
 * own bounds (`ContentReportShapingTest`, `report-worker/src/schema-parity.test.ts`) — this layer does
 * not re-validate, only serializes and posts.
 *
 * Best-effort: a failed send never undoes the local save, and the caller is expected to surface the
 * distinction between "saved" and "saved and sent" rather than treat this as fatal.
 *
 * [ENDPOINT] is a fixed, first-party address baked into the app, not an operator-supplied URL, so this
 * deliberately skips the SSRF pinning `cc.grepon.relais.batch.WebhookDelivery` needs for
 * operator-supplied webhook URLs — there is no attacker-controlled host here to redirect. Redirects are
 * disabled outright ([HttpURLConnection.setInstanceFollowRedirects]) so that claim holds even if the
 * origin were ever misconfigured to issue one, rather than resting on the destination staying fixed.
 */
object ContentReportDelivery {

  private const val TAG = "RelaisReportDelivery"
  private const val ENDPOINT = "https://report.ventouxlabs.com/report"
  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 20_000

  /**
   * The six fields `report-worker/src/index.ts`'s `parseReport` accepts, nothing else — an unknown
   * key would be silently dropped receiver-side anyway, but sending only what is allowlisted keeps
   * this file honest about what actually reaches the Worker. `note`/`modelId`/`backend` are omitted
   * entirely when null via [JSONObject.putOpt] rather than sent as JSON `null`; the Worker's
   * `isBoundedOrNull` treats a missing key and an explicit `null` identically, so either shape works,
   * but omitting is one fewer thing for a reader of the wire payload to puzzle over.
   *
   * Split out from [send] so the shaping is unit-testable without a network stack.
   */
  internal fun buildPayload(draft: ContentReportDraft, surface: String): String =
    JSONObject()
      .put("reasonId", draft.reasonId)
      .put("surface", surface)
      .put("excerpt", draft.excerpt)
      .putOpt("note", draft.note)
      .putOpt("modelId", draft.modelId)
      .putOpt("backend", draft.backend)
      .toString()

  /**
   * Blocking — call off the main thread. Returns whether the Worker answered 2xx.
   *
   * Catches [Exception], not just [java.io.IOException]: this is called from a caller-injected
   * function type ([cc.grepon.relais.ChatViewModel]'s `sendReport` parameter), so a future change on
   * either side of that seam — a null slipping into [JSONObject.put], a `ClassCastException` from the
   * `HttpURLConnection` cast — must not crash the app; "best-effort, never fatal" is this function's
   * whole contract. [CancellationException] is re-thrown first rather than swallowed, so a cancelled
   * send (the operator navigating away mid-request) is still handled by the coroutine machinery
   * instead of being reported as "could not reach the developer".
   */
  fun send(draft: ContentReportDraft, surface: String): Boolean =
    try {
      val body = buildPayload(draft, surface).toByteArray(Charsets.UTF_8)
      val conn =
        (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          doOutput = true
          instanceFollowRedirects = false
          connectTimeout = CONNECT_TIMEOUT_MS
          readTimeout = READ_TIMEOUT_MS
          setRequestProperty("Content-Type", "application/json")
        }
      try {
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        if (code !in 200..299) {
          Log.w(TAG, "report delivery rejected: HTTP $code")
          // Drain the error body so the connection can be reused rather than leaked.
          conn.errorStream?.use { it.readBytes() }
        }
        code in 200..299
      } finally {
        conn.disconnect()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "report delivery failed: ${e.message}")
      false
    }
}
