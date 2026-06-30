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

package cc.grepon.relais.customtasks.agentchat

import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Fetches a user-supplied `SKILL.md` with the SSRF DNS-rebinding window actually closed.
 *
 * [SkillUrlPolicy.resolvePinned] vets the host and hands back the exact [InetAddress] it approved; this
 * layer **connects to that pinned IP directly** instead of letting a second lookup pick the address —
 * so a name that flips public→private between the check and the connect never gets a second resolution.
 * For https it sets SNI + verifies the certificate against the ORIGINAL hostname (pinning the IP does
 * not weaken trust), and redirects are never followed. This is the GET twin of
 * [cc.grepon.relais.batch.WebhookDelivery] (which does the same for the signed-webhook POST); the
 * socket/TLS pinning block is intentionally kept in sync with it. The HTTP exchange itself lives in the
 * pure, JVM-tested [SkillHttp].
 */
object SkillSourceFetcher {

  private const val TAG = "RelaisSkillFetch"
  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 30_000

  sealed interface FetchResult {
    data class Success(val body: String) : FetchResult
    data class Failure(val message: String) : FetchResult
  }

  /**
   * Vets [url], connects to the pinned IP, and returns the SKILL.md body or a user-facing failure.
   * [resolve] is injected only so the (untested-on-JVM) socket path can be exercised on-device; the
   * vetting + parsing it delegates to are unit-tested.
   */
  fun fetch(
    url: String,
    resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
  ): FetchResult {
    val pin =
      when (val r = SkillUrlPolicy.resolvePinned(url, resolve)) {
        is SkillUrlPolicy.PinResult.Rejected -> {
          Log.w(TAG, "blocked unsafe skill URL")
          return FetchResult.Failure(r.message)
        }
        is SkillUrlPolicy.PinResult.Pinned -> r
      }

    val raw = Socket()
    return try {
      raw.connect(InetSocketAddress(pin.address, pin.port), CONNECT_TIMEOUT_MS)
      raw.soTimeout = READ_TIMEOUT_MS
      val socket: Socket =
        if (pin.https) {
          // 3-arg createSocket layers TLS over the already-connected pinned socket AND sets SNI to the
          // original hostname (not the IP) so SNI vhosts serve the right cert; startHandshake() still
          // does full PKIX validation against the system trust store. Mirrors WebhookDelivery.
          val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, pin.host, pin.port, true) as SSLSocket
          ssl.startHandshake()
          if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(pin.host, ssl.session)) {
            Log.w(TAG, "skill source TLS hostname verification failed for ${pin.host}")
            return FetchResult.Failure(SkillUrlPolicy.BLOCK_MESSAGE)
          }
          ssl
        } else {
          raw
        }
      toFailureOrBody(SkillHttp.fetch(pin.host, pin.target, socket.getOutputStream(), socket.getInputStream()))
    } catch (e: Exception) {
      Log.e(TAG, "error fetching SKILL.md: ${e.message}")
      FetchResult.Failure("Failed to fetch SKILL.md: ${e.message}")
    } finally {
      runCatching { raw.close() }
    }
  }

  private fun toFailureOrBody(outcome: SkillHttp.Outcome): FetchResult =
    when (outcome) {
      is SkillHttp.Outcome.Ok -> FetchResult.Success(outcome.body)
      is SkillHttp.Outcome.Redirected ->
        FetchResult.Failure("Skill URL redirected (HTTP 3xx); redirects aren't allowed for skill sources.")
      is SkillHttp.Outcome.HttpError -> FetchResult.Failure("Failed to fetch SKILL.md: HTTP ${outcome.code}")
      SkillHttp.Outcome.TooLarge ->
        FetchResult.Failure("SKILL.md is too large (max ${SkillHttp.MAX_BODY_BYTES / 1024} KB).")
      is SkillHttp.Outcome.Malformed -> FetchResult.Failure("Failed to fetch SKILL.md: malformed response")
    }
}
