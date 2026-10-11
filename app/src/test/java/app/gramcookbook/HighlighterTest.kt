package app.gramcookbook

import app.gramcookbook.gram.GramHighlighter
import app.gramcookbook.gram.GramHighlighter.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlighterTest {
    /** Each highlighted piece of text with its kind, in order. */
    private fun tokens(src: String) = GramHighlighter.highlight(src).map { src.substring(it.start, it.end) to it.kind }

    @Test fun frontmatterSectionsAndSteps() {
        val src = """
            ---
            title: 'Pain'
            ---

            ## Pâte ->&pâte

            [Mélanger] la @*farine{500g}, l'@eau{70% @&farine} et #saladier{} ~_{2h} ^{220°C}. ->&pâte // couvert

            Cuire la &pâte{1/2} avec @sel (fin) et @poivre:pepper{1 pincée}(moulu). /* note */
        """.trimIndent()
        assertEquals(
            listOf(
                "---" to Kind.META_DELIMITER, "title" to Kind.META_KEY, " 'Pain'" to Kind.META_VALUE, "---" to Kind.META_DELIMITER,
                "##" to Kind.SIGIL, " Pâte " to Kind.SECTION, "->&" to Kind.SIGIL, "pâte" to Kind.INTERMEDIATE,
                "[Mélanger]" to Kind.ACTION,
                "@*" to Kind.SIGIL, "farine" to Kind.INGREDIENT, "500" to Kind.NUMBER, "g" to Kind.UNIT,
                "@" to Kind.SIGIL, "eau" to Kind.INGREDIENT, "70" to Kind.NUMBER, "%" to Kind.SIGIL, "farine" to Kind.INTERMEDIATE,
                "#" to Kind.SIGIL, "saladier" to Kind.COOKWARE,
                "~_" to Kind.SIGIL, "2" to Kind.NUMBER, "h" to Kind.UNIT,
                "^" to Kind.SIGIL, "{220°C}" to Kind.TEMPERATURE,
                "->&" to Kind.SIGIL, "pâte" to Kind.INTERMEDIATE,
                "// couvert" to Kind.COMMENT,
                "&" to Kind.SIGIL, "pâte" to Kind.INTERMEDIATE, "1/2" to Kind.NUMBER,
                // Bare name: a space before the parenthesis means it is plain text.
                "@" to Kind.SIGIL, "sel" to Kind.INGREDIENT,
                "@" to Kind.SIGIL, "poivre" to Kind.INGREDIENT, "pepper" to Kind.ALIAS, "1" to Kind.NUMBER, "pincée" to Kind.UNIT, "(moulu)" to Kind.PREPARATION,
                "/* note */" to Kind.COMMENT,
            ),
            tokens(src),
        )
    }

    @Test fun multiLineBlockCommentAndPlainText() {
        assertEquals(
            listOf("/* a\nb */" to Kind.COMMENT, "@" to Kind.SIGIL, "x" to Kind.INGREDIENT),
            tokens("/* a\nb */ then @x."),
        )
        assertEquals(emptyList<Pair<String, Kind>>(), tokens("Plain text, a & b, 50% off."))
    }
}
