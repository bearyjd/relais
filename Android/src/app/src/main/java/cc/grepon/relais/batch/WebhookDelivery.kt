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
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Delivers a batch result to a webhook URL with the SSRF window actually closed.
 *
 * The [WebhookGuard] resolves + vets the host and hands back the exact [InetAddress] set; this layer
 * then **connects to one of those pinned IPs directly** instead of letting the socket re-resolve the
 * name. That is what closes the DNS-rebinding TOCTOU: the bytes the guard approved are the bytes we
 * connect to — a name that would flip to a private IP between resolve and connect never gets a second
 * lookup. For https we still set SNI + verify the certificate against the ORIGINAL hostname (so a public
 * webhook on a SNI vhost works and cert validation is honest), and redirects are never followed (a 3xx
 * could point at a private IP). The body is signed with HMAC-SHA256 ([WebhookSigner],
 * `X-Relais-Signature`). Best-effort: returns whether the receiver answered 2xx.
 */
object WebhookDelivery {

  private const val TAG = "RelaisWebhook"
  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 30_000
  private const val MAX_STATUS_LINE = 512 // bound the status-line read so a hostile receiver can't OOM us

  fun deliver(context: Context, url: String, payload: String): Boolean {
    val pinned = when (val verdict = WebhookGuard.check(url, RelaisConfig.webhookAllowlist(context))) {
      is WebhookGuard.Verdict.Blocked -> {
        Log.w(TAG, "webhook delivery blocked: ${verdict.reason}")
        return false
      }
      // Pin the FIRST address the guard vetted; the guard already rejected the host if ANY resolved
      // address was private (non-allowlist), so every entry here is safe to connect to.
      is WebhookGuard.Verdict.Allowed -> verdict.addresses.firstOrNull() ?: return false
    }
    val signature = WebhookSigner.header(payload, RelaisConfig.webhookHmacSecret(context))
    return try {
      val uri = URI(url)
      val https = uri.scheme.equals("https", ignoreCase = true)
      val host = uri.host ?: return false
      val port = if (uri.port != -1) uri.port else if (https) 443 else 80
      val rawPath = uri.rawPath.orEmpty().ifEmpty { "/" }
      val target = if (uri.rawQuery != null) "$rawPath?${uri.rawQuery}" else rawPath
      postPinned(pinned, port, https, host, target, payload, signature)
    } catch (e: Exception) {
      Log.w(TAG, "webhook POST failed: ${e.message}")
      false
    }
  }

  /**
   * POST to [addr]:[port] (the pinned IP), writing [host] as the HTTP `Host` header + (for https) the
   * TLS SNI, and verifying the cert against [host]. Reads only the status line — redirects are inherently
   * not followed (we never act on a 3xx), and the body is irrelevant for a best-effort signed delivery.
   */
  private fun postPinned(
    addr: InetAddress,
    port: Int,
    https: Boolean,
    host: String,
    target: String,
    payload: String,
    signature: String,
  ): Boolean {
    val raw = Socket()
    // `raw` is closed in the finally on EVERY path (handshake throw, hostname-fail, IO error, success).
    // For https `socket` is an SSLSocket created with autoClose=true wrapping `raw`, so closing `raw`
    // tears the TLS socket down too — no descriptor leak when startHandshake() throws on a bad cert.
    try {
      raw.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS)
      raw.soTimeout = READ_TIMEOUT_MS
      val socket: Socket =
        if (https) {
          // 3-arg createSocket(host, …) layers TLS over the already-connected pinned socket AND sets SNI
          // to the original hostname (not the IP), so SNI vhosts serve the right cert. startHandshake()
          // still performs full PKIX chain validation against the system trust store (pinning the IP
          // does not disable trust). NOTE: on Android getDefaultHostnameVerifier() is OkHostnameVerifier
          // (real RFC-2818 SAN/CN matching); on a plain JVM it is a no-op returning false — this path
          // only runs on Android.
          val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, host, port, true) as SSLSocket
          ssl.startHandshake()
          if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
            Log.w(TAG, "webhook TLS hostname verification failed for $host")
            return false
          }
          ssl
        } else {
          raw
        }
      val body = payload.toByteArray(Charsets.UTF_8)
      val request = buildString {
        append("POST $target HTTP/1.1\r\n")
        append("Host: $host\r\n")
        append("Content-Type: application/json\r\n")
        append("${WebhookSigner.HEADER}: $signature\r\n")
        append("Content-Length: ${body.size}\r\n")
        append("Connection: close\r\n\r\n")
      }
      socket.getOutputStream().apply {
        write(request.toByteArray(Charsets.US_ASCII))
        write(body)
        flush()
      }
      val statusLine = readStatusLine(socket.getInputStream()) ?: return false
      // "HTTP/1.1 200 OK" -> 200
      val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return false
      return (code in 200..299).also { if (!it) Log.w(TAG, "webhook receiver returned $code") }
    } finally {
      runCatching { raw.close() }
    }
  }

  /** Reads the HTTP status line (ASCII, up to '\n'), bounded to [MAX_STATUS_LINE] bytes so a receiver
   *  that never sends a newline can't grow the buffer without limit. The response body is ignored. */
  private fun readStatusLine(input: InputStream): String? {
    val sb = StringBuilder()
    var b = input.read()
    while (b != -1 && b != '\n'.code && sb.length < MAX_STATUS_LINE) {
      if (b != '\r'.code) sb.append(b.toChar())
      b = input.read()
    }
    return sb.toString().ifEmpty { null }
  }
}
