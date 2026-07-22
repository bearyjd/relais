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

package cc.grepon.relais

import org.json.JSONObject

/**
 * The ONE error envelope for every Relais HTTP error response (issue #173 item 3).
 *
 * Before this, three shapes coexisted: a flat `{"error":"str"}` on ~19 inline call sites in
 * [RelaisHttpServer], the nested OpenAI shape `{"error":{"message","type"}}` on the embeddings/rerank/
 * images routes, and `buildTtsError` (nested but returning a pre-serialized `String`, so it couldn't
 * compose with `.put(...)` for extra fields like `retry_after_seconds`). A client parsing `error` as an
 * object on one endpoint and a string on another is the kind of inconsistency OpenAI-compatible clients
 * (LiteLLM, Open WebUI, aider, ...) actively trip on.
 *
 * [json] is the single source of truth: every endpoint's error path builds its response through this
 * function (directly, or via a thin per-route wrapper like `buildRerankError` that keeps call sites
 * readable while delegating the actual shape here). Pure (no Android types) so it's covered by a fast
 * JVM unit test.
 */
object RelaisError {
  /** Malformed/invalid client request (400/413/422/431 paths). */
  const val INVALID_REQUEST = "invalid_request_error"

  /** Missing/incorrect credentials (401). */
  const val AUTHENTICATION = "authentication_error"

  /** Per-IP rate limit or admission-queue-full backpressure (429). */
  const val RATE_LIMIT_EXCEEDED = "rate_limit_exceeded"

  /** No route matches the request path (404). */
  const val NOT_FOUND = "not_found"

  /** The route exists but the capability isn't wired up on this node yet (501). */
  const val NOT_IMPLEMENTED = "not_implemented"

  /** Thermal shedding or a provisioning/exclusive-access wait — retry shortly (503). */
  const val SERVICE_UNAVAILABLE = "service_unavailable"

  /** Unexpected server-side failure; detail stays in logcat, never leaks to the caller (500). */
  const val INTERNAL_ERROR = "internal_error"

  /** `{"error":{"message":[message],"type":[type]}}` — the OpenAI-compatible error envelope. */
  fun json(message: String, type: String): JSONObject =
    JSONObject().put("error", JSONObject().put("message", message).put("type", type))
}
