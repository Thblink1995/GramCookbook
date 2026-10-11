package app.gramcookbook.gram

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The content of a quantity in braces: `{250g}`, `{1 1/2 cups}`, `{2-4}`, `{pincée}`,
 * `{70% @&flour}`. [low] is null when there is no number at all.
 */
data class Quantity(
    val low: Double?,
    val high: Double?,
    val unit: String,
    val relativePercent: Double? = null,
    val relativeTarget: String? = null,
) {
    val isEmpty: Boolean get() = low == null && unit.isEmpty() && relativePercent == null

    /** Relative quantities follow their target, so they never scale themselves. */
    fun scaled(factor: Double): Quantity =
        if (relativePercent != null || factor == 1.0) this
        else copy(low = low?.times(factor), high = high?.times(factor))

    fun format(): String = when {
        relativePercent != null -> "${formatNumber(relativePercent, "")} % $relativeTarget"
        low == null -> unit
        else -> buildString {
            append(formatNumber(low, unit))
            if (high != null) append("–").append(formatNumber(high, unit))
            if (unit.isNotEmpty()) append(' ').append(unit)
        }
    }

    companion object {
        private const val FRACTION_CHARS = "¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"
        private val FRACTION_VALUES = mapOf(
            '¼' to 0.25, '½' to 0.5, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3,
            '⅕' to 0.2, '⅖' to 0.4, '⅗' to 0.6, '⅘' to 0.8, '⅙' to 1.0 / 6, '⅚' to 5.0 / 6,
            '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
        )

        // Same number forms as the Gram grammar, plus a comma decimal separator.
        const val NUMBER = """(?:\d+\s+\d+/\d+|\d*[$FRACTION_CHARS]|\d+(?:[.,]\d+)?(?:/\d+)?)"""
        private val RELATIVE = Regex("""^($NUMBER)\s*%\s*@?&\s*(.+)$""")
        private val ABSOLUTE = Regex("""^($NUMBER)(?:\s*-\s*($NUMBER))?\s*(.*)$""")

        fun parse(text: String): Quantity {
            val t = text.trim()
            RELATIVE.matchEntire(t)?.let { m ->
                return Quantity(null, null, "", parseNumber(m.groupValues[1]), m.groupValues[2].trim())
            }
            val m = ABSOLUTE.matchEntire(t) ?: return Quantity(null, null, t)
            return Quantity(
                low = parseNumber(m.groupValues[1]),
                high = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseNumber),
                unit = m.groupValues[3].trim(),
            )
        }

        fun parseNumber(s: String): Double? {
            val t = s.trim().replace(',', '.')
            val parts = t.split(Regex("""\s+"""))
            if (parts.size == 2) return (parseNumber(parts[0]) ?: return null) + (parseNumber(parts[1]) ?: return null)
            val last = t.lastOrNull() ?: return null
            if (last in FRACTION_CHARS) {
                val whole = t.dropLast(1).toDoubleOrNull() ?: 0.0
                return whole + FRACTION_VALUES.getValue(last)
            }
            val slash = t.indexOf('/')
            if (slash > 0) {
                val n = t.substring(0, slash).toDoubleOrNull() ?: return null
                val d = t.substring(slash + 1).toDoubleOrNull() ?: return null
                return if (d == 0.0) null else n / d
            }
            return t.toDoubleOrNull()
        }

        private val METRIC = setOf("g", "gr", "kg", "mg", "ml", "cl", "dl", "l")
        private val GLYPHS = listOf(0.25 to "¼", 1.0 / 3 to "⅓", 0.5 to "½", 2.0 / 3 to "⅔", 0.75 to "¾")

        /** Metric amounts get decimals; spoons, cups and counts get kitchen fractions. */
        fun formatNumber(x: Double, unit: String): String {
            if (unit.lowercase() in METRIC) {
                return if (x >= 10) x.roundToInt().toString() else trimDecimals(x, 1)
            }
            val whole = floor(x).toInt()
            val frac = x - whole
            if (frac < 0.04) return if (whole == 0) trimDecimals(x, 2) else whole.toString()
            if (frac > 0.96) return (whole + 1).toString()
            GLYPHS.firstOrNull { abs(it.first - frac) < 0.04 }?.let { (_, glyph) ->
                return if (whole == 0) glyph else "$whole$glyph"
            }
            return trimDecimals(x, if (x < 1) 2 else 1)
        }

        private fun trimDecimals(x: Double, decimals: Int): String {
            val s = "%.${decimals}f".format(java.util.Locale.ROOT, x)
            return if ('.' in s) s.trimEnd('0').trimEnd('.') else s
        }
    }
}
