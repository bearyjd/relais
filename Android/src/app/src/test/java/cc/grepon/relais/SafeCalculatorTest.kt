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

import cc.grepon.relais.nodetools.SafeCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Pure JVM tests for [SafeCalculator] — the safe arithmetic evaluator behind the calculator tool. */
class SafeCalculatorTest {

  private fun eval(s: String) = SafeCalculator.evaluate(s)

  @Test fun `precedence and associativity`() {
    assertEquals(5.0, eval("2 + 3"), 1e-9)
    assertEquals(10.0, eval("2*3 + 4"), 1e-9)
    assertEquals(14.0, eval("2 + 3*4"), 1e-9)
    assertEquals(20.0, eval("(2 + 3) * 4"), 1e-9)
    assertEquals(2.5, eval("10 / 4"), 1e-9)
    assertEquals(1.0, eval("10 % 3"), 1e-9)
  }

  @Test fun `power is right-associative`() {
    assertEquals(1024.0, eval("2 ^ 10"), 1e-9)
    assertEquals(512.0, eval("2 ^ 3 ^ 2"), 1e-9) // 2^(3^2) = 2^9
  }

  @Test fun `unary and nesting`() {
    assertEquals(-2.0, eval("-5 + 3"), 1e-9)
    assertEquals(5.0, eval("-(-5)"), 1e-9)
    assertEquals(7.0, eval("1 + 2 * (6 / (3 - 1))"), 1e-9)
  }

  @Test fun `decimal and scientific literals`() {
    assertEquals(3.14, eval("3.14"), 1e-9)
    assertEquals(1000.0, eval("1e3"), 1e-9)
    assertEquals(0.0015, eval("1.5e-3"), 1e-12)
  }

  @Test fun `divide by zero is rejected as non-finite`() {
    for (s in listOf("1/0", "0/0", "5 % 0")) {
      try { eval(s); fail("expected throw for '$s'") } catch (e: IllegalArgumentException) {}
    }
  }

  @Test fun `non-arithmetic input is rejected (no code execution path)`() {
    for (s in listOf("", "   ", "abc", "2 +", "(2+3", "2)3", "1; 2", "import os", "2 + x", "sqrt(4)")) {
      try { eval(s); fail("expected throw for '$s'") } catch (e: IllegalArgumentException) {}
    }
  }

  @Test fun `negative-operand modulo follows the dividend sign`() {
    assertEquals(-1.0, eval("-7 % 3"), 1e-9)
  }

  @Test fun `excessive nesting throws cleanly instead of overflowing the stack`() {
    // Depth (70) exceeds the cap but stays under the length cap, so this exercises the depth guard.
    val expr = "(".repeat(70) + "1" + ")".repeat(70)
    try { eval(expr); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) {}
  }

  @Test fun `over-long input is rejected`() {
    try { eval("1+".repeat(600) + "1"); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) {}
  }
}
