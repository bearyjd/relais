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

package cc.grepon.relais.nodetools

import android.content.Context
import cc.grepon.relais.ToolSpec
import cc.grepon.relais.embed.EmbeddingGemmaEmbedder
import cc.grepon.relais.embed.RelaisEmbedderProvider
import cc.grepon.relais.rag.RagStore
import java.time.ZonedDateTime
import kotlin.math.abs
import org.json.JSONObject

/**
 * A built-in tool the NODE executes itself (single hop), as opposed to a client-supplied tool the node
 * only emits a call for. Each carries its OpenAI function [schema] (advertised to the model) and an
 * [execute] that returns a short content string fed back to the model for its final answer.
 *
 * The set is deliberately tiny and SAFE — deterministic, single-shot, no shell/filesystem/arbitrary
 * network — because the driving model (on-device Gemma-4-class) is weak at autonomous multi-step tool
 * loops; this is one-hop grounding, not an agent. (Feature #9.)
 */
interface NodeTool {
  val name: String

  /** The OpenAI `function` object: `{name, description, parameters}`. */
  fun schema(): JSONObject

  /** Runs the tool with the model-emitted [args]; returns content for the model (or an `error: …` string). */
  suspend fun execute(context: Context, args: JSONObject): String
}

object NodeTools {

  val ALL: List<NodeTool> = listOf(CalculatorTool, DateTimeTool, UnitConvertTool, RagSearchTool)
  private val index: Map<String, NodeTool> = ALL.associateBy { it.name }

  fun isBuiltin(name: String): Boolean = index.containsKey(name)

  fun byName(name: String): NodeTool? = index[name]

  /** The built-ins as [ToolSpec]s to advertise alongside any client-supplied tools. */
  fun toolSpecs(): List<ToolSpec> = ALL.map { ToolSpec(it.name, it.schema().toString()) }

  internal fun functionSchema(name: String, description: String, parametersJson: String): JSONObject =
    JSONObject()
      .put("name", name)
      .put("description", description)
      .put("parameters", JSONObject(parametersJson))

  /** Renders a Double without a spurious `.0` for whole numbers. */
  internal fun formatNumber(d: Double): String =
    if (d.isFinite() && d == d.toLong().toDouble() && abs(d) < 1e15) d.toLong().toString() else d.toString()
}

internal object CalculatorTool : NodeTool {
  override val name = "calculator"
  override fun schema() = NodeTools.functionSchema(
    name,
    "Evaluate an arithmetic expression. Supports + - * / % ^, parentheses, decimals and scientific notation.",
    """{"type":"object","properties":{"expression":{"type":"string","description":"e.g. (2+3)*4"}},"required":["expression"]}""",
  )

  override suspend fun execute(context: Context, args: JSONObject): String {
    val expr = NodeToolArgs.str(args, "expression")?.takeIf { it.isNotBlank() }
      ?: return "error: missing 'expression'"
    return try {
      "$expr = ${NodeTools.formatNumber(SafeCalculator.evaluate(expr))}"
    } catch (e: IllegalArgumentException) {
      "error: ${e.message}"
    }
  }
}

internal object DateTimeTool : NodeTool {
  override val name = "current_datetime"
  override fun schema() = NodeTools.functionSchema(
    name,
    "Return the device's current local date and time (ISO-8601 with offset).",
    """{"type":"object","properties":{}}""",
  )

  override suspend fun execute(context: Context, args: JSONObject): String = ZonedDateTime.now().toString()
}

internal object UnitConvertTool : NodeTool {
  override val name = "unit_convert"
  override fun schema() = NodeTools.functionSchema(
    name,
    "Convert a value between units of length, mass, or temperature (e.g. km↔mi, kg↔lb, C↔F).",
    """{"type":"object","properties":{"value":{"type":"number"},"from":{"type":"string"},"to":{"type":"string"}},"required":["value","from","to"]}""",
  )

  override suspend fun execute(context: Context, args: JSONObject): String {
    val value = NodeToolArgs.double(args, "value") ?: return "error: missing or non-numeric 'value'"
    if (!value.isFinite()) return "error: 'value' must be a finite number"
    val from = NodeToolArgs.str(args, "from")?.takeIf { it.isNotBlank() } ?: return "error: missing 'from'"
    val to = NodeToolArgs.str(args, "to")?.takeIf { it.isNotBlank() } ?: return "error: missing 'to'"
    return try {
      "${NodeTools.formatNumber(value)} $from = ${NodeTools.formatNumber(UnitConvert.convert(value, from, to))} $to"
    } catch (e: IllegalArgumentException) {
      "error: ${e.message}"
    }
  }
}

internal object RagSearchTool : NodeTool {
  override val name = "rag_search"
  override fun schema() = NodeTools.functionSchema(
    name,
    "Search the node's ingested document corpus for passages relevant to a query.",
    """{"type":"object","properties":{"query":{"type":"string"},"top_k":{"type":"integer","description":"how many passages (default 4)"}},"required":["query"]}""",
  )

  override suspend fun execute(context: Context, args: JSONObject): String {
    val query = NodeToolArgs.str(args, "query")?.takeIf { it.isNotBlank() } ?: return "error: missing 'query'"
    val topK = (NodeToolArgs.int(args, "top_k") ?: 4).coerceIn(1, 10)
    val embedder = RelaisEmbedderProvider.get() as? EmbeddingGemmaEmbedder
    if (embedder == null || !embedder.isAvailable(context)) return "error: the embeddings model is not available"
    val hits = RagStore.query(context, query, topK, embedder)
    if (hits.isEmpty()) return "No relevant passages found in the corpus."
    return hits.mapIndexed { i, h -> "[${i + 1}] ${h.text}" }.joinToString("\n")
  }
}
