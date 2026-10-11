package app.gramcookbook.gram

/**
 * A lenient reader for the subset of Gram the app displays. It follows the rules of the
 * official grammar (packages/parser in the Gram repo) for names, quantities and elements,
 * but never fails: anything it can't read stays as plain text.
 */
object GramParser {
    private const val SYNTAX_CHARS = "{}[]()<|:"
    private const val SIGILS = "@#~^&"
    private const val SENTENCE_PUNCT = ",;!?."
    private const val MODIFIERS = "?-*&="

    fun parse(source: String): Recipe {
        val lines = source.replace("\r\n", "\n").split('\n')
        var i = 0
        val meta = LinkedHashMap<String, String>()
        if (lines.firstOrNull()?.trim() == "---") {
            i = 1
            while (i < lines.size && lines[i].trim() != "---") {
                val l = lines[i]
                val colon = l.indexOf(':')
                if (colon > 0 && !l[0].isWhitespace()) meta[l.substring(0, colon).trim()] = unquote(l.substring(colon + 1).trim())
                i++
            }
            i++
        }

        val sections = mutableListOf<Section>()
        var title: String? = null
        var steps = mutableListOf<Step>()
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            parseStep(paragraph.joinToString("\n"))?.let(steps::add)
            paragraph.clear()
        }

        fun flushSection() {
            flushParagraph()
            if (title != null || steps.isNotEmpty()) sections += Section(title, steps)
            steps = mutableListOf()
        }

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("## ") -> {
                    flushSection()
                    title = sectionTitle(line.substring(3))
                }
                line.isBlank() -> flushParagraph()
                line.startsWith("@use ") && sections.isEmpty() && steps.isEmpty() -> Unit
                else -> paragraph += line
            }
            i++
        }
        flushSection()
        return Recipe(meta, sections)
    }

    /** Reads only the frontmatter, for fast listing. */
    fun readMeta(source: String): Map<String, String> {
        val meta = LinkedHashMap<String, String>()
        val lines = source.lineSequence().iterator()
        if (!lines.hasNext() || lines.next().trim() != "---") return meta
        for (l in lines) {
            if (l.trim() == "---") break
            val colon = l.indexOf(':')
            if (colon > 0 && !l[0].isWhitespace()) meta[l.substring(0, colon).trim()] = unquote(l.substring(colon + 1).trim())
        }
        return meta
    }

    fun readTitle(source: String): String? = readMeta(source)["title"]?.takeIf { it.isNotBlank() }

    fun unquote(v: String): String = when {
        v.length >= 2 && v.startsWith('\'') && v.endsWith('\'') -> v.substring(1, v.length - 1).replace("''", "'")
        v.length >= 2 && v.startsWith('"') && v.endsWith('"') -> v.substring(1, v.length - 1).replace("\\\"", "\"")
        else -> v
    }

    /** YAML flow lists (`['a', 'b']`) as plain values. */
    fun listValues(v: String?): List<String> {
        if (v.isNullOrBlank()) return emptyList()
        if (!(v.startsWith('[') && v.endsWith(']'))) return listOf(v)
        return v.substring(1, v.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
    }

    private fun sectionTitle(raw: String): String {
        val cut = listOf(raw.indexOf("->&"), raw.indexOf("~{")).filter { it >= 0 }.minOrNull() ?: raw.length
        return raw.substring(0, cut).trim()
    }

    private fun parseStep(paragraph: String): Step? {
        var body = paragraph.trimStart()
        var action: String? = null
        if (body.startsWith('[')) {
            val close = body.indexOf(']')
            if (close > 1 && '\n' !in body.substring(0, close)) {
                action = body.substring(1, close).trim()
                body = body.substring(close + 1).trimStart(' ', '\t')
            }
        }
        val parts = parseInline(body)
        if (action == null && parts.isEmpty()) return null
        return Step(action, parts)
    }

    fun parseInline(s: String): List<Part> {
        val out = mutableListOf<Part>()
        val text = StringBuilder()
        fun emit(p: Part) {
            if (text.isNotEmpty()) {
                out += Part.Text(text.toString())
                text.clear()
            }
            out += p
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            val result: Pair<Part, Int>? = when {
                s.startsWith("//", i) -> {
                    val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    Part.Comment(s.substring(i + 2, end).trim()) to end
                }
                s.startsWith("/*", i) -> {
                    val end = s.indexOf("*/", i + 2)
                    if (end < 0) null else Part.Comment(s.substring(i + 2, end).trim()) to end + 2
                }
                s.startsWith("->&", i) -> output(s, i)
                c == '@' && !s.startsWith("@use \"", i) -> ingredient(s, i)
                c == '#' -> cookware(s, i)
                c == '~' -> timer(s, i)
                c == '^' -> temperature(s, i)
                c == '&' -> reference(s, i)
                else -> null
            }
            if (result != null) {
                emit(result.first)
                i = result.second
            } else {
                text.append(
                    when (c) {
                        '\n', '\t' -> ' '
                        '|' -> '/'
                        else -> c
                    },
                )
                i++
            }
        }
        if (text.isNotEmpty()) out += Part.Text(text.toString())
        return out
    }

    private fun scan(s: String, from: Int, accept: (Char) -> Boolean): Int {
        var j = from
        while (j < s.length && accept(s[j])) j++
        return j
    }

    private fun isRefNameChar(c: Char) = c !in SYNTAX_CHARS && c !in SIGILS && c != '\n'
    private fun isNameChar(c: Char) = c !in SYNTAX_CHARS && c != '\n'
    private fun isWordChar(c: Char) = c !in SYNTAX_CHARS && !c.isWhitespace() && c !in SENTENCE_PUNCT

    /** `{...}` starting at [at], on a single line. Returns the content and the index after `}`. */
    private fun braces(s: String, at: Int): Pair<String, Int>? {
        if (at >= s.length || s[at] != '{') return null
        val close = s.indexOf('}', at)
        if (close < 0) return null
        val inner = s.substring(at + 1, close)
        if ('\n' in inner) return null
        return inner to close + 1
    }

    private fun parenthesis(s: String, at: Int): Pair<String, Int>? {
        if (at >= s.length || s[at] != '(') return null
        val close = s.indexOf(')', at)
        if (close < 0) return null
        return s.substring(at + 1, close).trim() to close + 1
    }

    /** Name with optional alias and braces (full form), or a single word (bare form). */
    private class Named(val name: String, val alias: String?, val braces: String?, val end: Int)

    private fun named(s: String, from: Int): Named? {
        val nameEnd = scan(s, from, ::isRefNameChar)
        if (nameEnd > from) {
            var k = nameEnd
            var alias: String? = null
            if (k < s.length && s[k] == ':') {
                val aliasEnd = scan(s, k + 1, ::isRefNameChar)
                if (aliasEnd > k + 1) {
                    alias = s.substring(k + 1, aliasEnd).trim()
                    k = aliasEnd
                }
            }
            braces(s, k)?.let { (inner, end) ->
                return Named(s.substring(from, nameEnd).trim(), alias, inner, end)
            }
        }
        val wordEnd = scan(s, from, ::isWordChar)
        if (wordEnd == from) return null
        var k = wordEnd
        var alias: String? = null
        if (k + 1 < s.length && s[k] == ':' && isWordChar(s[k + 1])) {
            val aliasEnd = scan(s, k + 1, ::isWordChar)
            alias = s.substring(k + 1, aliasEnd)
            k = aliasEnd
        }
        return Named(s.substring(from, wordEnd), alias, null, k)
    }

    private fun modifiersEnd(s: String, from: Int) = scan(s, from) { it in MODIFIERS }

    private fun ingredient(s: String, at: Int): Pair<Part, Int>? {
        val modEnd = modifiersEnd(s, at + 1)
        val n = named(s, modEnd) ?: return null
        var end = n.end
        val prep = parenthesis(s, end)?.also { end = it.second }?.first
        // A composite parent (`<@lemon`) only matters for shopping, which the app doesn't do.
        if (s.startsWith("<@", end)) {
            val parentMods = modifiersEnd(s, end + 2)
            named(s, parentMods)?.let { p -> end = parenthesis(s, p.end)?.second ?: p.end }
        }
        val part = Part.Ingredient(
            name = n.name,
            display = n.alias ?: n.name,
            quantity = n.braces?.let(Quantity::parse),
            modifiers = s.substring(at + 1, modEnd),
            preparation = prep,
        )
        return part to end
    }

    private fun cookware(s: String, at: Int): Pair<Part, Int>? {
        val modEnd = modifiersEnd(s, at + 1)
        val n = named(s, modEnd) ?: return null
        val end = parenthesis(s, n.end)?.second ?: n.end
        return Part.Cookware(n.name, n.alias ?: n.name, s.substring(at + 1, modEnd)) to end
    }

    private fun timer(s: String, at: Int): Pair<Part, Int>? {
        var k = at + 1
        val passive = k < s.length && s[k] == '_'
        if (passive) k++
        val nameEnd = scan(s, k, ::isNameChar)
        val (inner, end) = braces(s, nameEnd) ?: return null
        val name = s.substring(k, nameEnd).trim().takeIf { it.isNotEmpty() }
        return Part.Timer(name, passive, inner.trim()) to end
    }

    private fun temperature(s: String, at: Int): Pair<Part, Int>? {
        val nameEnd = scan(s, at + 1, ::isNameChar)
        val (inner, end) = braces(s, nameEnd) ?: return null
        return Part.Temperature(inner.trim()) to end
    }

    private fun reference(s: String, at: Int): Pair<Part, Int>? {
        val n = named(s, at + 1) ?: return null
        return Part.Reference(n.name, n.braces?.let(Quantity::parse)) to n.end
    }

    private fun output(s: String, at: Int): Pair<Part, Int>? {
        val from = at + 3
        val nameEnd = scan(s, from, ::isNameChar)
        if (nameEnd > from && s.startsWith("{}", nameEnd)) return Part.Output(s.substring(from, nameEnd).trim()) to nameEnd + 2
        val wordEnd = scan(s, from, ::isWordChar)
        if (wordEnd == from) return null
        return Part.Output(s.substring(from, wordEnd)) to wordEnd
    }
}
