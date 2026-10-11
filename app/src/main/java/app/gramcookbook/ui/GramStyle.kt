package app.gramcookbook.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
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

/** Colors of the rendered recipe, from the Gram renderer's gram.css (light and dark). */
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

private val RecipeDark = RecipeColors(
    ingredient = Color(0xFFC68AFF),
    cookware = Color(0xFF60A5FA),
    timer = Color(0xFFFBBF24),
    temperature = Color(0xFFF87171),
    intermediate = Color(0xFF34D399),
)

@Composable
fun recipeColors(): RecipeColors = if (isSystemInDarkTheme()) RecipeDark else RecipeLight

/** Editor colors: the playground highlights Gram with Shiki's github-light / github-dark themes. */
private fun editorStyle(kind: Kind, dark: Boolean): SpanStyle {
    fun c(light: Long, darkColor: Long) = Color(if (dark) darkColor else light)
    val red = c(0xFFD73A49, 0xFFF97583)
    val purple = c(0xFF6F42C1, 0xFFB392F0)
    val blue = c(0xFF005CC5, 0xFF79B8FF)
    val green = c(0xFF22863A, 0xFF85E89D)
    val string = c(0xFF032F62, 0xFF9ECBFF)
    // github-dark-high-contrast's comment color, as patched in the playground for contrast.
    val comment = c(0xFF6A737D, 0xFFBDC4CC)
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
class GramVisualTransformation(private val dark: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val styled = AnnotatedString.Builder(raw).apply {
            for (s in GramHighlighter.highlight(raw)) {
                val start = s.start.coerceIn(0, raw.length)
                val end = s.end.coerceIn(start, raw.length)
                if (end > start) addStyle(editorStyle(s.kind, dark), start, end)
            }
        }.toAnnotatedString()
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is GramVisualTransformation && other.dark == dark
    override fun hashCode() = dark.hashCode()
}
