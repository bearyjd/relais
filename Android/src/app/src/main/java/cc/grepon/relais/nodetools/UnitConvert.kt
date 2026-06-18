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

/**
 * Deterministic unit conversion for the node-side `unit_convert` tool — length, mass, and temperature.
 * Pure: a fixed table of factors to a per-category base unit (metres, grams), with explicit formulas
 * for temperature (which is affine, not a simple ratio). Unknown or cross-category units throw
 * [IllegalArgumentException].
 */
object UnitConvert {

  // Aliases → canonical unit. Length base = metre, mass base = gram.
  private val LENGTH = mapOf(
    "m" to 1.0, "meter" to 1.0, "metre" to 1.0, "meters" to 1.0, "metres" to 1.0,
    "km" to 1000.0, "kilometer" to 1000.0, "kilometre" to 1000.0,
    "cm" to 0.01, "centimeter" to 0.01, "centimetre" to 0.01,
    "mm" to 0.001, "millimeter" to 0.001, "millimetre" to 0.001,
    "mi" to 1609.344, "mile" to 1609.344, "miles" to 1609.344,
    "yd" to 0.9144, "yard" to 0.9144, "yards" to 0.9144,
    "ft" to 0.3048, "foot" to 0.3048, "feet" to 0.3048,
    "in" to 0.0254, "inch" to 0.0254, "inches" to 0.0254,
  )
  private val MASS = mapOf(
    "g" to 1.0, "gram" to 1.0, "grams" to 1.0,
    "kg" to 1000.0, "kilogram" to 1000.0, "kilograms" to 1000.0,
    "mg" to 0.001, "milligram" to 0.001,
    "lb" to 453.59237, "lbs" to 453.59237, "pound" to 453.59237, "pounds" to 453.59237,
    "oz" to 28.349523125, "ounce" to 28.349523125, "ounces" to 28.349523125,
  )
  private val TEMP = setOf("c", "celsius", "f", "fahrenheit", "k", "kelvin")

  /** Converts [value] from unit [from] to unit [to] (case/whitespace-insensitive). */
  fun convert(value: Double, from: String, to: String): Double {
    val f = from.trim().lowercase()
    val t = to.trim().lowercase()
    LENGTH[f]?.let { ff -> LENGTH[t]?.let { tt -> return value * ff / tt } }
    MASS[f]?.let { ff -> MASS[t]?.let { tt -> return value * ff / tt } }
    if (f in TEMP && t in TEMP) return convertTemp(value, f, t)
    throw IllegalArgumentException("cannot convert '$from' to '$to' (unknown or incompatible units)")
  }

  private fun convertTemp(value: Double, from: String, to: String): Double {
    val celsius = when {
      from.startsWith("c") -> value
      from.startsWith("f") -> (value - 32.0) * 5.0 / 9.0
      else -> value - 273.15 // kelvin
    }
    return when {
      to.startsWith("c") -> celsius
      to.startsWith("f") -> celsius * 9.0 / 5.0 + 32.0
      else -> celsius + 273.15
    }
  }
}
