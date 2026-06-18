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

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 signing of batch webhook payloads, so a receiver can verify a delivery genuinely came
 * from this node (GitHub/Stripe-style). The signature is sent in the `X-Relais-Signature` header as
 * `sha256=<lowercase-hex>`; the receiver recomputes `HMAC-SHA256(secret, body)` over the EXACT raw
 * body bytes and compares.
 */
object WebhookSigner {

  const val HEADER = "X-Relais-Signature"

  /** Lowercase hex of `HMAC-SHA256(secret, payload)`. */
  fun sign(payload: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }

  /** The `X-Relais-Signature` header value: `sha256=<hex>`. */
  fun header(payload: String, secret: String): String = "sha256=" + sign(payload, secret)
}
