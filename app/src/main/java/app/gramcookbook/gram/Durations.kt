package app.gramcookbook.gram

/**
 * Recipe times from the frontmatter: `prepTime: 15` (minutes, as `gram import` writes them),
 * and leniently `prep time: 1h30`, `cookTime: 45 min`, `totalTime: PT1H15M`.
 */
object Durations {
    enum class Kind { PREP, COOK, TOTAL }

    private val KEYS = mapOf(
        "preptime" to Kind.PREP, "preparationtime" to Kind.PREP,
        "cooktime" to Kind.COOK, "cookingtime" to Kind.COOK,
        "totaltime" to Kind.TOTAL, "time" to Kind.TOTAL, "timerequired" to Kind.TOTAL, "duration" to Kind.TOTAL,
    )

    private val ISO = Regex("""^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:[\d.]+S)?)?$""", RegexOption.IGNORE_CASE)
    private val TEXT = Regex("""^(?:(\d+)\s*(?:h|hr|hrs|hours?|heures?)\s*)?(?:(\d+)\s*(?:m|mn|min|mins|minutes?)?)?$""", RegexOption.IGNORE_CASE)

    /** The times a recipe gives, in display order; each as minutes when readable, else as written. */
    fun of(meta: Map<String, String>): Map<Kind, String> {
        val found = sortedMapOf<Kind, String>()
        for ((key, value) in meta) {
            val kind = KEYS[key.lowercase().filter { it.isLetter() }] ?: continue
            if (value.isBlank() || kind in found) continue
            found[kind] = minutes(value)?.let(::format) ?: value.trim()
        }
        return found
    }

    /** `15`, `15.0`, `1h30`, `90 min`, `PT1H30M` -> minutes; null when unreadable or zero. */
    fun minutes(value: String): Int? {
        val v = value.trim()
        v.toDoubleOrNull()?.let { return it.toInt().takeIf { m -> m > 0 } }
        ISO.matchEntire(v)?.takeIf { v.length > 2 }?.let { m ->
            val (d, h, min) = m.destructured
            return ((d.toIntOrNull() ?: 0) * 1440 + (h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0)).takeIf { it > 0 }
        }
        TEXT.matchEntire(v)?.takeIf { v.isNotEmpty() }?.let { m ->
            val (h, min) = m.destructured
            return ((h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0)).takeIf { it > 0 }
        }
        return null
    }

    /** `45 min`, `1 h`, `1 h 30`. */
    fun format(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${"%02d".format(minutes % 60)}"
    }
}
