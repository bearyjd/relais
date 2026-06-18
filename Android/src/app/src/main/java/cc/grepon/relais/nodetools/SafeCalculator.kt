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

import kotlin.math.pow

/**
 * A SAFE arithmetic expression evaluator for the node-side `calculator` tool. Hand-rolled
 * recursive-descent over a fixed grammar — `+ - * / % ^`, parentheses, unary +/-, decimal/scientific
 * literals — so it evaluates ONLY arithmetic. There is no `eval`, no reflection, no identifiers, no
 * function calls: a hostile model (or user) string can't reach code execution, the filesystem, or the
 * network. Anything outside the grammar throws [IllegalArgumentException].
 *
 * Grammar (precedence low→high):
 *   expr   = term  (('+' | '-') term)*
 *   term   = power (('*' | '/' | '%') power)*
 *   power  = unary ('^' power)?            // right-associative
 *   unary  = ('+' | '-') unary | primary
 *   primary= number | '(' expr ')'
 */
object SafeCalculator {

  /** Max input length + parenthesis-nesting depth — bounds CPU and the parser's recursion (no stack overflow). */
  private const val MAX_CHARS = 1024
  private const val MAX_DEPTH = 64

  /** Evaluates [expression]; throws [IllegalArgumentException] on anything that isn't valid arithmetic. */
  fun evaluate(expression: String): Double {
    require(expression.length <= MAX_CHARS) { "expression too long (max $MAX_CHARS chars)" }
    val p = Parser(expression)
    val v = p.parseExpr()
    p.expectEnd()
    if (!v.isFinite()) throw IllegalArgumentException("result is not finite (overflow or divide-by-zero)")
    return v
  }

  private class Parser(private val s: String) {
    private var pos = 0
    private var depth = 0

    fun parseExpr(): Double {
      if (++depth > MAX_DEPTH) throw IllegalArgumentException("expression nested too deeply (max $MAX_DEPTH)")
      try {
        var acc = parseTerm()
        while (true) {
          when (peek()) {
            '+' -> { pos++; acc += parseTerm() }
            '-' -> { pos++; acc -= parseTerm() }
            else -> return acc
          }
        }
      } finally {
        depth--
      }
    }

    private fun parseTerm(): Double {
      var acc = parsePower()
      while (true) {
        when (peek()) {
          '*' -> { pos++; acc *= parsePower() }
          '/' -> { pos++; acc /= parsePower() }
          '%' -> { pos++; acc = acc.rem(parsePower()) }
          else -> return acc
        }
      }
    }

    private fun parsePower(): Double {
      val base = parseUnary()
      return if (peek() == '^') { pos++; base.pow(parsePower()) } else base
    }

    private fun parseUnary(): Double =
      when (peek()) {
        '+' -> { pos++; parseUnary() }
        '-' -> { pos++; -parseUnary() }
        else -> parsePrimary()
      }

    private fun parsePrimary(): Double {
      val c = peek() ?: throw IllegalArgumentException("unexpected end of expression")
      if (c == '(') {
        pos++
        val v = parseExpr()
        if (peek() != ')') throw IllegalArgumentException("missing ')' at index $pos")
        pos++
        return v
      }
      return parseNumber()
    }

    private fun parseNumber(): Double {
      val start = pos
      while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
      // optional scientific exponent: e[+/-]digits
      if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
        pos++
        if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
        if (pos >= s.length || !s[pos].isDigit()) throw IllegalArgumentException("malformed exponent at index $pos")
        while (pos < s.length && s[pos].isDigit()) pos++
      }
      if (pos == start) throw IllegalArgumentException("expected a number at index $start: '${s[start]}'")
      return s.substring(start, pos).toDoubleOrNull()
        ?: throw IllegalArgumentException("invalid number '${s.substring(start, pos)}'")
    }

    /** Next non-space char (advancing past whitespace), or null at end. */
    private fun peek(): Char? {
      while (pos < s.length && s[pos].isWhitespace()) pos++
      return if (pos < s.length) s[pos] else null
    }

    fun expectEnd() {
      if (peek() != null) throw IllegalArgumentException("unexpected '${s[pos]}' at index $pos")
    }
  }
}
