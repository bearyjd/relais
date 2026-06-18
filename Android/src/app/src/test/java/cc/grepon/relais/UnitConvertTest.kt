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

import cc.grepon.relais.nodetools.UnitConvert
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Pure JVM tests for [UnitConvert] — the node-side unit_convert tool. */
class UnitConvertTest {

  @Test fun `length conversions`() {
    assertEquals(1000.0, UnitConvert.convert(1.0, "km", "m"), 1e-9)
    assertEquals(1.609344, UnitConvert.convert(1.0, "mi", "km"), 1e-9)
    assertEquals(1.0, UnitConvert.convert(12.0, "in", "ft"), 1e-9)
    assertEquals(100.0, UnitConvert.convert(1.0, "m", "cm"), 1e-9)
  }

  @Test fun `mass conversions`() {
    assertEquals(1000.0, UnitConvert.convert(1.0, "kg", "g"), 1e-9)
    assertEquals(16.0, UnitConvert.convert(1.0, "lb", "oz"), 1e-6)
  }

  @Test fun `temperature conversions are affine`() {
    assertEquals(32.0, UnitConvert.convert(0.0, "c", "f"), 1e-9)
    assertEquals(212.0, UnitConvert.convert(100.0, "celsius", "fahrenheit"), 1e-9)
    assertEquals(0.0, UnitConvert.convert(32.0, "f", "c"), 1e-9)
    assertEquals(273.15, UnitConvert.convert(0.0, "c", "k"), 1e-9)
    assertEquals(0.0, UnitConvert.convert(273.15, "kelvin", "celsius"), 1e-9)
  }

  @Test fun `case and whitespace insensitive`() {
    assertEquals(1000.0, UnitConvert.convert(1.0, " KM ", "M"), 1e-9)
  }

  @Test fun `cross-category and unknown units throw`() {
    for (pair in listOf("m" to "kg", "c" to "m", "furlong" to "m", "kg" to "c")) {
      try {
        UnitConvert.convert(1.0, pair.first, pair.second)
        fail("expected throw for ${pair.first}->${pair.second}")
      } catch (e: IllegalArgumentException) {}
    }
  }
}
