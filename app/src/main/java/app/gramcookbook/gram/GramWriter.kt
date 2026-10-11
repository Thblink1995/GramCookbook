package app.gramcookbook.gram

/** A recipe in plain language, as typed in the form or read from a web page. */
data class RecipeDraft(
    val title: String = "",
    val portions: String = "",
    val ingredients: String = "",
    val steps: String = "",
    val category: String = "",
    val source: String = "",
    val author: String = "",
)

data class ParsedIngredient(val name: String, val quantity: String, val preparation: String?, val optional: Boolean = false)

/**
 * Turns a [RecipeDraft] into Gram: each ingredient is placed where a step first mentions it
 * (`la farine` -> `la @farine{250g}`); the ones never mentioned go into a first step so they
 * still appear in the ingredient list.
 */
object GramWriter {
    private const val FRACTIONS = "¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"
    private const val NUM = """(?:\d+\s+\d+/\d+|\d*[$FRACTIONS]|\d+(?:[.,]\d+)?(?:\s*/\s*\d+)?)"""
    private val LEADING_QTY = Regex("""^($NUM)(?:\s*(?:-|–|à|to)\s*($NUM))?\s*""")
    private val ARTICLE_ONE = Regex("""^(?:un|une|a|an|one)\s+""", RegexOption.IGNORE_CASE)
    private val ADJECTIVE = Regex("""^(?:bonnes?|grosses?|petites?|belles?|heaping|heaped|level|generous|rounded)\s+""", RegexOption.IGNORE_CASE)
    private val ALTERNATIVE = Regex("""\s(?:ou|or)\s""", RegexOption.IGNORE_CASE)
    private val OF = Regex("""^(?:de\s+|d['’]\s*|of\s+)""", RegexOption.IGNORE_CASE)

    // Unit spellings mapped to the short form written in the recipe. Order matters: longest first.
    private val UNITS: List<Pair<Regex, String?>> = listOf(
        """cuill?[èe]res?\s+[àa]\s+soupe|c\.?\s?[àa]\.?\s?s(?:oupe)?\.?|càs|cas""" to "c.à.s",
        """cuill?[èe]res?\s+[àa]\s+caf[ée]|c\.?\s?[àa]\.?\s?c(?:af[ée])?\.?|càc|cac""" to "c.à.c",
        """tablespoons?|tbsps?|tbs""" to "tbsp",
        """teaspoons?|tsps?""" to "tsp",
        """cups?""" to "cup",
        """kilogrammes?|kilograms?|kilos?|kg""" to "kg",
        """grammes?|grams?|gr|g""" to "g",
        """mg""" to "mg",
        """millilitres?|milliliters?|ml""" to "ml",
        """centilitres?|cl""" to "cl",
        """décilitres?|dl""" to "dl",
        """litres?|liters?|l""" to "l",
        """ounces?|oz""" to "oz",
        """pounds?|lbs?""" to "lb",
        // Kept as written (plural included): any word is a valid Gram unit.
        """pinc[ée]es?|pinch(?:es)?|sachets?|tranches?|slices?|gousses?|cloves?|feuilles?|branches?|brins?|bottes?|bo[iî]tes?|cans?|verres?|bols?|poign[ée]es?|handfuls?|noix|filets?|morceaux|morceau|pieces?|bunch(?:es)?|sprigs?""" to null,
    ).map { (pattern, short) -> Regex("""^(?:$pattern)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE) to short }

    private val STOP_WORDS = setOf(
        "de", "du", "des", "la", "le", "les", "en", "au", "aux", "et", "of", "the", "and", "fresh", "frais", "fraîche",
        "gros", "grosse", "petit", "petite", "large", "small", "big",
    )

    private val OPTIONAL = Regex("""^(?:facultatif|facultative|optionnel|optionnelle|optional|si désiré|if desired)$""", RegexOption.IGNORE_CASE)

    fun parseIngredient(line: String, french: Boolean = looksFrench(line)): ParsedIngredient? {
        var rest = line.trim().trimStart('-', '•', '*', '·').trim()
        if (rest.isEmpty() || rest.endsWith(':')) return null

        var qty = ""
        LEADING_QTY.find(rest)?.let { m ->
            qty = normalizeNumber(m.groupValues[1]) + m.groupValues[2].takeIf { it.isNotEmpty() }?.let { "-" + normalizeNumber(it) }.orEmpty()
            rest = rest.substring(m.range.last + 1)
        } ?: ARTICLE_ONE.find(rest)?.let { m ->
            qty = "1"
            rest = rest.substring(m.range.last + 1)
        }

        if (qty.isNotEmpty()) {
            // "1 bonne cuillère à café": the adjective stays with the unit.
            val adjective = ADJECTIVE.find(rest)?.value.orEmpty()
            for ((regex, short) in UNITS) {
                val m = regex.find(rest.substring(adjective.length)) ?: continue
                val unit = adjective.trim().lowercase().let { if (it.isEmpty()) "" else "$it " } + (short ?: m.value.lowercase())
                qty = if (short == null || unit.length > 2) "$qty $unit" else "$qty$unit"
                rest = rest.substring(adjective.length + m.range.last + 1).trimStart(' ', '.')
                break
            }
            rest = rest.replace(OF, "")
        }

        var prep: String? = null
        Regex("""\(([^()]*)\)""").find(rest)?.let { m ->
            prep = m.groupValues[1].trim().takeIf { it.isNotEmpty() }
            rest = rest.removeRange(m.range)
        }
        if (qty.isNotEmpty() && ',' in rest) {
            val extra = rest.substringAfter(',').trim()
            rest = rest.substringBefore(',')
            if (extra.isNotEmpty()) prep = listOfNotNull(prep, extra).joinToString(", ")
        }

        // "huile ou beurre fondu": the first one is the ingredient, the rest a note.
        ALTERNATIVE.find(rest)?.let { m ->
            val other = rest.substring(m.range.last + 1).replace(OF, "").trim()
            rest = rest.substring(0, m.range.first)
            if (other.isNotEmpty()) prep = listOfNotNull(prep, m.value.trim() + " " + other).joinToString(", ")
        }

        val name = cleanName(rest, french)
        if (name.isEmpty()) return null
        val optional = prep?.let { OPTIONAL.matches(it.trim()) } == true
        return ParsedIngredient(name, qty, if (optional) null else prep?.let(::cleanPreparation), optional)
    }

    fun toGram(draft: RecipeDraft): String {
        val french = looksFrench(draft.ingredients + "\n" + draft.steps)
        val ingredients = draft.ingredients.lines().mapNotNull { parseIngredient(it, french) }
        val lines = draft.steps.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val steps = lines.map { line -> if (line.startsWith("##")) null else Woven(sanitizeProse(line)) }

        val unplaced = mutableListOf<ParsedIngredient>()
        for (ing in ingredients) {
            if (steps.none { it != null && it.place(ing) }) unplaced += ing
        }

        val out = StringBuilder()
        out.append("---\n")
        out.append("title: ").append(yamlQuote(draft.title.trim().ifEmpty { if (french) "Sans titre" else "Untitled" })).append('\n')
        draft.portions.trim().toIntOrNull()?.takeIf { it > 0 }?.let { out.append("portions: ").append(it).append('\n') }
        if (draft.category.isNotBlank()) out.append("category: ").append(yamlQuote(draft.category.trim())).append('\n')
        if (draft.author.isNotBlank()) out.append("author: ").append(yamlQuote(draft.author.trim())).append('\n')
        if (draft.source.isNotBlank()) out.append("source: ").append(yamlQuote(draft.source.trim())).append('\n')
        out.append("---\n")

        if (unplaced.isNotEmpty()) {
            out.append('\n').append(if (french) "Ingrédients : " else "Ingredients: ")
            out.append(unplaced.joinToString(", ") { token(it, null) }).append(".\n")
        }
        lines.forEachIndexed { i, line ->
            out.append('\n')
            val step = steps[i]
            if (step == null) out.append("## ").append(sectionTitle(line)).append('\n')
            else out.append(step.text()).append('\n')
        }
        return out.toString()
    }

    /** A step whose mentions of ingredients get replaced by Gram tokens. */
    private class Woven(text: String) {
        private val sb = StringBuilder(text)
        // Normalized copy used for matching; replaced spans are blanked out with \u0000.
        private val search = StringBuilder(text.map(::fold).joinToString(""))

        fun place(ing: ParsedIngredient): Boolean {
            for (candidate in candidates(ing.name)) {
                val m = Regex("""(?<![\p{L}\p{N}])${Regex.escape(candidate)}(?:s|x|es)?(?![\p{L}\p{N}])""").find(search) ?: continue
                val surface = sb.substring(m.range.first, m.range.last + 1)
                val t = token(ing, surface)
                sb.replace(m.range.first, m.range.last + 1, t)
                search.replace(m.range.first, m.range.last + 1, "\u0000".repeat(t.length))
                return true
            }
            return false
        }

        fun text() = sb.toString()
    }

    private fun candidates(name: String): List<String> {
        val folded = name.map(::fold).joinToString("")
        val words = folded.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val singular = words.joinToString(" ") { singular(it) }
        // The main noun comes first in French ("sucre en poudre"), last in English ("chicken stock").
        val significant = words.filter { it.length >= 3 && it !in STOP_WORDS }.map(::singular)
        return listOfNotNull(folded, singular, significant.firstOrNull(), significant.lastOrNull())
            .filter { it.length >= 2 }.distinct()
    }

    private fun singular(w: String) =
        if (w.length > 3 && (w.endsWith('s') || w.endsWith('x'))) w.dropLast(1) else w

    /** Lowercase without accents, one char for one char so indices stay aligned. */
    private fun fold(c: Char): Char {
        val base = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).first()
        return base.lowercaseChar()
    }

    private fun token(ing: ParsedIngredient, surface: String?): String = buildString {
        append('@')
        if (ing.optional) append('?')
        append(ing.name)
        if (surface != null && surface != ing.name) append(':').append(surface)
        append('{').append(ing.quantity).append('}')
        ing.preparation?.let { append('(').append(it).append(')') }
    }

    private fun normalizeNumber(n: String) = n.trim().replace(',', '.').replace(Regex("""\s*/\s*"""), "/")

    /** Gram names can't contain its delimiters or element sigils. */
    private fun cleanName(s: String, french: Boolean) = s.replace("&", if (french) " et " else " and ")
        .replace(Regex("""[{}\[\]()<|:@#~^]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim().trimEnd('.', ',', ';')
        .let { if (it.length > 1 && it[0].isUpperCase() && it[1].isLowerCase()) it[0].lowercaseChar() + it.substring(1) else it }

    private fun cleanPreparation(s: String) = s.replace(Regex("""[()]"""), " ").replace(Regex("""\s+"""), " ").trim()

    /** Free text must not accidentally start Gram elements or comments. */
    fun sanitizeProse(s: String): String = s
        .replace("&", if (looksFrench(s)) "et" else "and")
        .replace("@", "a")
        .replace("#", "n°")
        .replace("~", "≈")
        .replace("^", "")
        .replace("|", "/")
        .replace("//", "/​/")
        .replace("/*", "/ *")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .let { if (it.startsWith("##")) it.trimStart('#').trim() else it }

    private fun sectionTitle(line: String) = line.trimStart('#').trim()
        .replace("->&", "")
        .replace("~{", "{")
        .ifEmpty { "Section" }

    /** Sets (or removes, when [value] is blank) one frontmatter key, leaving the rest untouched. */
    fun setMeta(source: String, key: String, value: String?): String {
        val line = value?.trim()?.takeIf { it.isNotEmpty() }?.let { "$key: " + yamlQuote(it) }
        val lines = source.replace("\r\n", "\n").split('\n').toMutableList()
        if (lines.firstOrNull()?.trim() != "---") return if (line == null) source else "---\n$line\n---\n\n$source"
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return source
        val i = (1 until end).firstOrNull { lines[it].startsWith("$key:") }
        when {
            i != null && line != null -> lines[i] = line
            i != null -> lines.removeAt(i)
            line != null -> lines.add(end, line)
        }
        return lines.joinToString("\n")
    }

    fun yamlQuote(s: String) = "'" + s.replace("\n", " ").replace("'", "''") + "'"

    fun looksFrench(text: String): Boolean {
        val t = " " + text.lowercase() + " "
        val fr = listOf(" de ", " la ", " le ", " les ", " et ", " dans ", " du ", " une ").sumOf { Regex(Regex.escape(it)).findAll(t).count() }
        val en = listOf(" the ", " and ", " of ", " in ", " with ", " a ", " to ").sumOf { Regex(Regex.escape(it)).findAll(t).count() }
        return fr > en
    }
}
