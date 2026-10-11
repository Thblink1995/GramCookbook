package app.gramcookbook.gram

import java.util.regex.Pattern

/**
 * Syntax highlighting for the raw editor: splits Gram source into token ranges, using the
 * same categories as the official TextMate grammar (packages/parser/syntaxes) so colors
 * can follow the playground's editor.
 */
object GramHighlighter {
    enum class Kind {
        SIGIL, // @ # ~ ^ & ->&, and modifiers (keyword.control / storage.modifier)
        INGREDIENT, // entity.name.type.ingredient
        COOKWARE, // support.class.cookware
        TIMER_NAME, // support.type.timer
        INTERMEDIATE, // entity.name.tag.reference / intermediate
        NUMBER, // constant.numeric
        UNIT, // keyword.other.unit
        PREPARATION, // string.other.preparation
        ALIAS, // string.other.alias
        TEMPERATURE, // constant.numeric.temperature
        ACTION, // entity.name.function.action
        SECTION, // entity.name.section / markup.heading
        COMMENT,
        META_KEY, // frontmatter keys
        META_VALUE,
        META_DELIMITER,
    }

    data class Span(val start: Int, val end: Int, val kind: Kind)

    private const val NAME = """[^{}\[\]()<|:@#~^&\n]"""
    private const val WORD = """[^{}\[\]()<|:\s,;!?.]"""
    private const val MODS = """[?\-*&=]*"""
    private val FULL_ITEM = Pattern.compile("""([@#])($MODS)($NAME+)(?::($NAME+))?\{([^}\n]*)\}(\([^)\n]*\))?""")
    private val BARE_ITEM = Pattern.compile("""([@#])($MODS)($WORD+)(\([^)\n]*\))?""")
    private val TIMER = Pattern.compile("""(~_?)([^{}\[\]()<|:\n]*)\{([^}\n]*)\}""")
    private val TEMPERATURE = Pattern.compile("""\^([^{}\[\]()<|:\n]*)\{([^}\n]*)\}""")
    private val OUTPUT = Pattern.compile("""->&(?:($NAME+)\{\}|($WORD+))""")
    private val REFERENCE = Pattern.compile("""&(?:($NAME+)\{([^}\n]*)\}|($WORD+))""")
    private val NUMBER = Regex("""^\s*(\d+\s+\d+/\d+|\d*[¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞]|\d+(?:[.,]\d+)?(?:/\d+)?)(?:\s*-\s*(\d+(?:[.,]\d+)?(?:/\d+)?))?""")

    fun highlight(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0

        // Frontmatter: `key: value` lines between two `---`.
        if (text.startsWith("---\n") || text.startsWith("---\r\n")) {
            val close = Regex("""(?m)^---[ \t]*$""").findAll(text).drop(1).firstOrNull()
            if (close != null) {
                out += Span(0, 3, Kind.META_DELIMITER)
                var lineStart = text.indexOf('\n') + 1
                while (lineStart < close.range.first) {
                    val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                    val colon = text.indexOf(':', lineStart)
                    if (colon in lineStart until lineEnd && !text[lineStart].isWhitespace()) {
                        out += Span(lineStart, colon, Kind.META_KEY)
                        if (colon + 1 < lineEnd) out += Span(colon + 1, lineEnd, Kind.META_VALUE)
                    }
                    lineStart = lineEnd + 1
                }
                out += Span(close.range.first, close.range.last + 1, Kind.META_DELIMITER)
                i = close.range.last + 1
            }
        }

        var atLineStart = true
        while (i < text.length) {
            val c = text[i]
            if (atLineStart && text.startsWith("## ", i)) {
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                out += Span(i, i + 2, Kind.SIGIL)
                // Highlight `->&name` and `~{-1d}` inside the title.
                val inner = highlightInline(text, i + 2, end)
                var from = i + 2
                for (s in inner) {
                    if (s.start > from) out += Span(from, s.start, Kind.SECTION)
                    out += s
                    from = s.end
                }
                if (end > from) out += Span(from, end, Kind.SECTION)
                i = end
                continue
            }
            if (atLineStart && c == '[') {
                val close = text.indexOf(']', i)
                val nl = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                if (close in i + 2 until nl) {
                    out += Span(i, close + 1, Kind.ACTION)
                    i = close + 1
                    atLineStart = false
                    continue
                }
            }
            val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            val spans = highlightInline(text, i, end)
            out += spans
            // A block comment can run over several lines: resume after it.
            i = maxOf(end + 1, spans.lastOrNull()?.end ?: 0)
            atLineStart = true
        }
        return out
    }

    /** Elements and comments between [from] and [to] (one line, or a block comment beyond it). */
    private fun highlightInline(text: String, from: Int, to: Int): List<Span> {
        val out = mutableListOf<Span>()
        var i = from
        while (i < to) {
            val c = text[i]
            val next = when {
                text.startsWith("//", i) -> {
                    out += Span(i, to, Kind.COMMENT)
                    to
                }
                text.startsWith("/*", i) -> {
                    val close = text.indexOf("*/", i + 2)
                    val e = if (close < 0) text.length else close + 2
                    out += Span(i, e, Kind.COMMENT)
                    // A block comment may run past the line; the caller continues after it.
                    if (e > to) return out
                    e
                }
                text.startsWith("->&", i) -> output(text, i, to, out)
                c == '@' || c == '#' -> item(text, i, to, out)
                c == '~' -> timer(text, i, to, out)
                c == '^' -> temperature(text, i, to, out)
                c == '&' -> reference(text, i, to, out)
                else -> -1
            }
            i = if (next > i) next else i + 1
        }
        return out
    }

    private fun Pattern.at(text: String, i: Int, to: Int) =
        matcher(text).region(i, to).takeIf { it.lookingAt() }

    private fun item(text: String, i: Int, to: Int, out: MutableList<Span>): Int {
        val ingredient = text[i] == '@'
        val nameKind = if (ingredient) Kind.INGREDIENT else Kind.COOKWARE
        FULL_ITEM.at(text, i, to)?.let { m ->
            out += Span(m.start(1), m.end(2), Kind.SIGIL)
            out += Span(m.start(3), m.end(3), nameKind)
            if (m.start(4) >= 0) out += Span(m.start(4), m.end(4), Kind.ALIAS)
            quantity(text, m.start(5), m.end(5), out)
            if (m.start(6) >= 0) out += Span(m.start(6), m.end(6), Kind.PREPARATION)
            return m.end()
        }
        BARE_ITEM.at(text, i, to)?.let { m ->
            out += Span(m.start(1), m.end(2), Kind.SIGIL)
            out += Span(m.start(3), m.end(3), nameKind)
            if (m.start(4) >= 0) out += Span(m.start(4), m.end(4), Kind.PREPARATION)
            return m.end()
        }
        return -1
    }

    private fun timer(text: String, i: Int, to: Int, out: MutableList<Span>): Int {
        val m = TIMER.at(text, i, to) ?: return -1
        out += Span(m.start(1), m.end(1), Kind.SIGIL)
        if (m.end(2) > m.start(2)) out += Span(m.start(2), m.end(2), Kind.TIMER_NAME)
        quantity(text, m.start(3), m.end(3), out)
        return m.end()
    }

    private fun temperature(text: String, i: Int, to: Int, out: MutableList<Span>): Int {
        val m = TEMPERATURE.at(text, i, to) ?: return -1
        out += Span(i, i + 1, Kind.SIGIL)
        out += Span(m.start(2) - 1, m.end(2) + 1, Kind.TEMPERATURE)
        return m.end()
    }

    private fun output(text: String, i: Int, to: Int, out: MutableList<Span>): Int {
        val m = OUTPUT.at(text, i, to) ?: return -1
        out += Span(i, i + 3, Kind.SIGIL)
        out += Span(i + 3, m.end(), Kind.INTERMEDIATE)
        return m.end()
    }

    private fun reference(text: String, i: Int, to: Int, out: MutableList<Span>): Int {
        val m = REFERENCE.at(text, i, to) ?: return -1
        out += Span(i, i + 1, Kind.SIGIL)
        if (m.start(1) >= 0) {
            out += Span(m.start(1), m.end(1), Kind.INTERMEDIATE)
            quantity(text, m.start(2), m.end(2), out)
        } else {
            out += Span(m.start(3), m.end(3), Kind.INTERMEDIATE)
        }
        return m.end()
    }

    /** Inside braces: the number(s), then the unit. */
    private fun quantity(text: String, from: Int, to: Int, out: MutableList<Span>) {
        val inner = text.substring(from, to)
        val m = NUMBER.find(inner)
        var unitFrom = from
        if (m != null) {
            out += Span(from + m.range.first, from + m.range.last + 1, Kind.NUMBER)
            unitFrom = from + m.range.last + 1
        }
        val unit = text.substring(unitFrom, to)
        val lead = unit.length - unit.trimStart().length
        val trimmed = unit.trim()
        if (trimmed.isEmpty()) return
        // Relative quantity: `70% @&flour`.
        val pct = trimmed.indexOf('%')
        if (pct >= 0) {
            out += Span(unitFrom + lead + pct, unitFrom + lead + pct + 1, Kind.SIGIL)
            val target = trimmed.substring(pct + 1)
            val nameAt = target.indexOfFirst { it != ' ' && it != '@' && it != '&' }
            if (nameAt >= 0) out += Span(unitFrom + lead + pct + 1 + nameAt, unitFrom + lead + trimmed.length, Kind.INTERMEDIATE)
            return
        }
        out += Span(unitFrom + lead, unitFrom + lead + trimmed.length, Kind.UNIT)
    }
}
