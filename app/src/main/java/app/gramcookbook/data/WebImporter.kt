package app.gramcookbook.data

import app.gramcookbook.gram.RecipeDraft
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

class ImportException(message: String) : Exception(message)

/**
 * Reads the schema.org `Recipe` that most recipe sites embed as JSON-LD (the same data
 * `gram import` starts from), without any AI: ingredients and steps are kept as written.
 */
object WebImporter {
    fun fetch(url: String): RecipeDraft {
        val html = download(url)
        return fromHtml(html, url) ?: throw ImportException("no-recipe")
    }

    fun fromHtml(html: String, url: String): RecipeDraft? {
        val scripts = Regex(
            """<script[^>]*type\s*=\s*["']?application/ld\+json["']?[^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).map { it.groupValues[1] }
        for (script in scripts) {
            val json = script.trim().removePrefix("<!--").removeSuffix("-->").trim()
            val parsed = try {
                JSONTokener(json).nextValue()
            } catch (e: Exception) {
                continue
            }
            findRecipe(parsed)?.let { return toDraft(it, url) }
        }
        return null
    }

    private fun download(url: String): String {
        var current = URL(url.trim())
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            conn.setRequestProperty("Accept-Language", "fr,en;q=0.8")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw ImportException("http-$code")
                    current = URL(current, location)
                    return@repeat
                }
                if (code !in 200..299) throw ImportException("http-$code")
                val charset = Regex("""charset=([\w-]+)""").find(conn.contentType.orEmpty())?.groupValues?.get(1) ?: "UTF-8"
                return conn.inputStream.use { it.readBytes() }.toString(charset(charset))
            } finally {
                conn.disconnect()
            }
        }
        throw ImportException("too-many-redirects")
    }

    private fun isRecipe(o: JSONObject): Boolean = when (val t = o.opt("@type")) {
        is String -> t == "Recipe" || t.endsWith("/Recipe")
        is JSONArray -> (0 until t.length()).any { t.optString(it) == "Recipe" }
        else -> false
    }

    private fun findRecipe(v: Any?): JSONObject? = when (v) {
        is JSONObject -> if (isRecipe(v)) v else v.keys().asSequence().firstNotNullOfOrNull { findRecipe(v.opt(it)) }
        is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { findRecipe(v.opt(it)) }
        else -> null
    }

    private fun toDraft(r: JSONObject, url: String): RecipeDraft {
        val ingredients = strings(r.opt("recipeIngredient") ?: r.opt("ingredients"))
        val steps = mutableListOf<String>()
        instructions(r.opt("recipeInstructions"), steps)
        val yields = strings(r.opt("recipeYield"))
        val portions = yields.firstNotNullOfOrNull { Regex("""\d+""").find(it)?.value }.orEmpty()
        return RecipeDraft(
            title = clean(r.optString("name")),
            portions = portions,
            ingredients = ingredients.joinToString("\n"),
            steps = steps.joinToString("\n"),
            source = url.trim(),
            author = author(r.opt("author")),
            category = strings(r.opt("recipeCategory")).firstOrNull().orEmpty(),
        )
    }

    private fun strings(v: Any?): List<String> = when (v) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until v.length()).flatMap { strings(v.opt(it)) }
        is JSONObject -> listOfNotNull(v.optString("text").ifEmpty { v.optString("name") }.takeIf { it.isNotEmpty() }).map(::clean)
        else -> listOf(clean(v.toString()))
    }.filter { it.isNotBlank() }

    private fun author(v: Any?): String = when (v) {
        is JSONArray -> (0 until v.length()).map { author(v.opt(it)) }.filter { it.isNotBlank() }.joinToString(", ")
        is JSONObject -> clean(v.optString("name"))
        is String -> clean(v)
        else -> ""
    }

    private fun instructions(v: Any?, out: MutableList<String>) {
        when (v) {
            is String -> clean(v.replace(Regex("""<br\s*/?>|</p>|</li>""", RegexOption.IGNORE_CASE), "\n"), keepLines = true)
                .lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach(out::add)
            is JSONArray -> for (i in 0 until v.length()) instructions(v.opt(i), out)
            is JSONObject -> {
                val type = v.opt("@type")?.toString().orEmpty()
                if ("HowToSection" in type) {
                    clean(v.optString("name")).takeIf { it.isNotEmpty() }?.let { out += "## $it" }
                    instructions(v.opt("itemListElement"), out)
                } else {
                    val text = v.optString("text").ifEmpty { v.optString("name") }
                    if (text.isNotEmpty()) instructions(text, out)
                    else instructions(v.opt("itemListElement"), out)
                }
            }
        }
    }

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "agrave" to "à", "acirc" to "â", "ccedil" to "ç",
        "ocirc" to "ô", "ucirc" to "û", "icirc" to "î", "iuml" to "ï", "euml" to "ë", "ugrave" to "ù", "oelig" to "œ",
        "deg" to "°", "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "rsquo" to "’", "lsquo" to "‘",
        "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»", "hellip" to "…", "ndash" to "–", "mdash" to "—",
    )

    fun clean(s: String, keepLines: Boolean = false): String {
        var t = s.replace(Regex("""</?(?:p|br|li|div|ul|ol|h\d)\b[^>]*>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<[^>]+>"""), "")
        // Twice, for sites that double-encode (&amp;eacute;).
        repeat(2) {
            t = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+\d*);""").replace(t) { m ->
                val e = m.groupValues[1]
                when {
                    e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                    e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                    else -> ENTITIES[e] ?: m.value
                }
            }
        }
        t = t.replace(' ', ' ')
        return if (keepLines) t.lines().joinToString("\n") { it.replace(Regex("""[ \t]+"""), " ").trim() }
        else t.replace(Regex("""\s+"""), " ").trim()
    }
}
