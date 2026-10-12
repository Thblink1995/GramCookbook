package app.gramcookbook.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import app.gramcookbook.gram.GramHighlighter
import app.gramcookbook.gram.GramHighlighter.Kind

/** Colors of the rendered recipe, from the Gram renderer's gram.css (light: the pages are always paper). */
@Immutable
data class RecipeColors(
    val ingredient: Color,
    val cookware: Color,
    val timer: Color,
    val temperature: Color,
    val intermediate: Color,
)

private val RecipeLight = RecipeColors(
    ingredient = Color(0xFF7C3AED),
    cookware = Color(0xFF2563EB),
    timer = Color(0xFFD97706),
    temperature = Color(0xFFDC2626),
    intermediate = Color(0xFF059669),
)

fun recipeColors(): RecipeColors = RecipeLight

/** Editor colors: the playground highlights Gram with Shiki's github-light theme (the editor is paper too). */
private fun editorStyle(kind: Kind): SpanStyle {
    val red = Color(0xFFD73A49)
    val purple = Color(0xFF6F42C1)
    val blue = Color(0xFF005CC5)
    val green = Color(0xFF22863A)
    val string = Color(0xFF032F62)
    val comment = Color(0xFF6A737D)
    return when (kind) {
        Kind.SIGIL, Kind.UNIT -> SpanStyle(color = red)
        Kind.INGREDIENT, Kind.ACTION -> SpanStyle(color = purple)
        Kind.COOKWARE, Kind.TIMER_NAME, Kind.NUMBER, Kind.TEMPERATURE -> SpanStyle(color = blue)
        Kind.SECTION -> SpanStyle(color = blue, fontWeight = FontWeight.Bold)
        Kind.INTERMEDIATE, Kind.META_KEY -> SpanStyle(color = green)
        Kind.PREPARATION, Kind.ALIAS, Kind.META_VALUE -> SpanStyle(color = string)
        Kind.COMMENT -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
        Kind.META_DELIMITER -> SpanStyle(color = comment)
    }
}

/** Colors the editor's text without changing it (offsets map one to one). */
object GramVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val styled = AnnotatedString.Builder(raw).apply {
            for (s in GramHighlighter.highlight(raw)) {
                val start = s.start.coerceIn(0, raw.length)
                val end = s.end.coerceIn(start, raw.length)
                if (end > start) addStyle(editorStyle(s.kind), start, end)
            }
        }.toAnnotatedString()
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
