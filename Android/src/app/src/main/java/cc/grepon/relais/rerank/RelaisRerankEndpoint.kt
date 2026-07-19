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

package cc.grepon.relais.rerank

import org.json.JSONArray
import org.json.JSONObject

// POST /v1/rerank helpers (issue #177). Cohere/Jina-style rerank (the de-facto shape LiteLLM/LocalAI
// speak). Pure (no Android types) so parsing + ordering + response shaping are unit-tested on the JVM.

/** Bounds for one rerank request. */
data class RerankLimits(
  val maxDocuments: Int = 128,
  val maxQueryChars: Int = 8 * 1024,
  val maxDocChars: Int = 32 * 1024,
)

val RERANK_LIMITS = RerankLimits()

/** A validated rerank request. [topN] null → return all (ranked); [returnDocuments] echoes the text back. */
data class RerankRequest(
  val query: String,
  val documents: List<String>,
  val topN: Int?,
  val returnDocuments: Boolean,
)

sealed interface RerankRequestResult {
  data class Valid(val request: RerankRequest) : RerankRequestResult
  data class Invalid(val message: String) : RerankRequestResult
}

/**
 * Parse + validate a Cohere/Jina `/v1/rerank` body. `query` required non-blank ≤ [RerankLimits.maxQueryChars];
 * `documents` a non-empty array of strings (each ≤ [RerankLimits.maxDocChars], ≤ [RerankLimits.maxDocuments]
 * of them) — supports both `["a","b"]` and Cohere's `[{"text":"a"}]` object form; `top_n` optional (clamped
 * ≥1); `return_documents` optional bool (default false). `model` accepted + ignored (the node serves its voice).
 */
fun parseRerankRequest(body: JSONObject, limits: RerankLimits = RERANK_LIMITS): RerankRequestResult {
  val query = body.optString("query", "")
  if (query.isBlank()) return RerankRequestResult.Invalid("'query' is required")
  if (query.length > limits.maxQueryChars) {
    return RerankRequestResult.Invalid("'query' exceeds ${limits.maxQueryChars} characters")
  }
  val docsArr = body.optJSONArray("documents")
    ?: return RerankRequestResult.Invalid("'documents' is required (a non-empty array)")
  if (docsArr.length() == 0) return RerankRequestResult.Invalid("'documents' must be non-empty")
  if (docsArr.length() > limits.maxDocuments) {
    return RerankRequestResult.Invalid("too many documents (max ${limits.maxDocuments})")
  }
  val documents = ArrayList<String>(docsArr.length())
  for (i in 0 until docsArr.length()) {
    // Accept a plain string OR Cohere's {"text": "..."} object form.
    val text = when (val el = docsArr.get(i)) {
      is String -> el
      is JSONObject -> el.optString("text", "")
      else -> ""
    }
    if (text.isBlank()) return RerankRequestResult.Invalid("document at index $i is empty")
    if (text.length > limits.maxDocChars) {
      return RerankRequestResult.Invalid("document at index $i exceeds ${limits.maxDocChars} characters")
    }
    documents.add(text)
  }
  val topN = if (body.has("top_n") && !body.isNull("top_n")) body.optInt("top_n").coerceAtLeast(1) else null
  val returnDocuments = body.optBoolean("return_documents", false)
  return RerankRequestResult.Valid(RerankRequest(query, documents, topN, returnDocuments))
}

/**
 * Maps a cosine similarity (`[-1, 1]`) to the `[0, 1]` `relevance_score` the Cohere/Jina contract
 * promises clients (~1 = highly relevant). Monotonic, so it never changes the ranking order.
 */
fun cosineToRelevance(cos: Float): Float = ((cos + 1f) / 2f).coerceIn(0f, 1f)

/**
 * Orders document indices by [scores] descending (highest relevance first), keeping ≤ [topN] (all when
 * null). Stable on ties by original index. Pure + unit-tested — decoupled from how the scores were computed.
 */
fun rerankOrder(scores: FloatArray, topN: Int?): List<Int> {
  val order = scores.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
  val n = topN?.coerceIn(0, order.size) ?: order.size
  return order.take(n)
}

/**
 * Cohere/Jina-shaped response: `{ "object":"list", "model":…, "results":[{index, relevance_score,
 * document?}] }`. [documents] is the ORIGINAL input order (results carry the original `index`); the
 * echoed `document` object is included only when the client asked (`return_documents`).
 */
fun buildRerankResponse(
  order: List<Int>,
  scores: FloatArray,
  documents: List<String>,
  returnDocuments: Boolean,
  model: String,
): JSONObject {
  val results = JSONArray()
  for (idx in order) {
    val r = JSONObject().put("index", idx).put("relevance_score", scores[idx].toDouble())
    if (returnDocuments) r.put("document", JSONObject().put("text", documents[idx]))
    results.put(r)
  }
  return JSONObject().put("object", "list").put("model", model).put("results", results)
}

/** OpenAI-style error envelope for the rerank route's 400/501/503 paths. */
fun buildRerankError(message: String, type: String): JSONObject =
  JSONObject().put("error", JSONObject().put("message", message).put("type", type))
