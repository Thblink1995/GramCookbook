package app.gramcookbook.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** The book's materials: a wooden desk, a leather cover, paper and ink, and the index tabs. */
@Immutable
data class BookPalette(
    val deskTop: Color,
    val deskBottom: Color,
    val leather: Color,
    val leatherDark: Color,
    val gold: Color,
    val paper: Color,
    val paperEdge: Color,
    val paperBack: Color,
    val shade: Color,
    val ink: Color,
    val inkMuted: Color,
    val tabs: List<Color>,
    val tabNone: Color,
    val tabInk: Color,
    val seal: Color,
    val sealDark: Color,
)

private val Tabs = listOf(
    Color(0xFFB5583A), // terracotta
    Color(0xFF7A8F5C), // sage
    Color(0xFFC99A2E), // mustard
    Color(0xFF5D7F99), // dusty blue
    Color(0xFF8A4F6E), // plum
    Color(0xFF4F8A80), // teal
    Color(0xFFB07A45), // ochre
    Color(0xFF6B6F8C), // slate
)

/** Recycled paper whatever the system theme: the pages never turn dark. */
private val Paper = BookPalette(
    deskTop = Color(0xFF4A3426),
    deskBottom = Color(0xFF2B1D15),
    leather = Color(0xFF6E2F24),
    leatherDark = Color(0xFF3F1A14),
    gold = Color(0xFFD4B26A),
    paper = Color(0xFFE9E2D2),
    paperEdge = Color(0xFFD5CAB2),
    paperBack = Color(0xFFDFD6C3),
    shade = Color(0xFF5A4126),
    ink = Color(0xFF33281C),
    inkMuted = Color(0xFF76664F),
    tabs = Tabs,
    tabNone = Color(0xFFA89A82),
    tabInk = Color(0xFFFBF5E8),
    seal = Color(0xFFA3262A),
    sealDark = Color(0xFF6B1518),
)

val LocalBookPalette = staticCompositionLocalOf { Paper }

private fun scheme(p: BookPalette): ColorScheme {
    val variant = lerp(p.paper, p.paperEdge, 0.6f)
    val rubric = Color(0xFF9E3B26)
    return lightColorScheme(
        primary = rubric,
        onPrimary = p.paper,
        primaryContainer = lerp(p.paper, rubric, 0.14f),
        onPrimaryContainer = p.ink,
        secondary = p.inkMuted,
        onSecondary = p.paper,
        secondaryContainer = variant,
        onSecondaryContainer = p.ink,
        tertiary = p.inkMuted,
        background = p.paper,
        onBackground = p.ink,
        surface = p.paper,
        onSurface = p.ink,
        surfaceVariant = variant,
        onSurfaceVariant = p.inkMuted,
        surfaceTint = Color.Transparent,
        surfaceBright = p.paper,
        surfaceDim = variant,
        surfaceContainerLowest = p.paper,
        surfaceContainerLow = p.paper,
        surfaceContainer = lerp(p.paper, p.paperEdge, 0.3f),
        surfaceContainerHigh = lerp(p.paper, p.paperEdge, 0.45f),
        surfaceContainerHighest = variant,
        inverseSurface = p.ink,
        inverseOnSurface = p.paper,
        outline = lerp(p.inkMuted, p.paper, 0.25f),
        outlineVariant = lerp(p.paperEdge, p.inkMuted, 0.25f),
        errorContainer = Color(0xFFF4D6C8),
        onErrorContainer = Color(0xFF5A1A10),
    )
}

private fun TextStyle.serif() = copy(fontFamily = FontFamily.Serif)

private val SerifTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.serif(), displayMedium = displayMedium.serif(), displaySmall = displaySmall.serif(),
        headlineLarge = headlineLarge.serif(), headlineMedium = headlineMedium.serif(), headlineSmall = headlineSmall.serif(),
        titleLarge = titleLarge.serif(), titleMedium = titleMedium.serif(), titleSmall = titleSmall.serif(),
        bodyLarge = bodyLarge.serif(), bodyMedium = bodyMedium.serif(), bodySmall = bodySmall.serif(),
        labelLarge = labelLarge.serif(), labelMedium = labelMedium.serif(), labelSmall = labelSmall.serif(),
    )
}

/** A fixed paper-and-ink palette (no dynamic colors: the book has its own materials). */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBookPalette provides Paper) {
        MaterialTheme(colorScheme = scheme(Paper), typography = SerifTypography, content = content)
    }
}

/** Bits of old print and colored stock left in recycled pulp. */
private val PulpFibers = listOf(Color(0xFF3B5A8C), Color(0xFF9C3B33), Color(0xFF4F7A55), Color(0xFF2E2A26))

/** A sheet of recycled paper: grey fibers, dark specks and a few colored threads, a slightly aged edge, and the shadow of the binding on the left. */
fun Modifier.paper(p: BookPalette): Modifier = drawWithCache {
    val random = Random(7)
    val area = size.width * size.height
    val fibers = List((area / 1_200f).toInt().coerceAtMost(1_400)) {
        val x = random.nextFloat() * size.width
        val y = random.nextFloat() * size.height
        val len = (3 + random.nextFloat() * 12).dp.toPx()
        val angle = random.nextFloat() * PI.toFloat()
        val colored = random.nextFloat() < 0.04f
        val color = if (colored) PulpFibers[random.nextInt(PulpFibers.size)].copy(alpha = 0.25f + random.nextFloat() * 0.2f)
        else p.shade.copy(alpha = 0.04f + random.nextFloat() * 0.07f)
        Triple(Offset(x, y), Offset(x + len * kotlin.math.cos(angle), y + len * sin(angle)), color)
    }
    val specks = List((area / 4_000f).toInt().coerceAtMost(500)) {
        val center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
        val radius = (0.3f + random.nextFloat() * random.nextFloat() * 1.2f).dp.toPx()
        Triple(center, radius, p.shade.copy(alpha = 0.12f + random.nextFloat() * 0.3f))
    }
    // Uneven pulp: broad, faint blotches lighter and darker than the sheet.
    val blotches = List(14) {
        val center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
        val radius = (40 + random.nextFloat() * 120).dp.toPx()
        val tint = if (random.nextBoolean()) Color.White.copy(alpha = 0.10f) else p.shade.copy(alpha = 0.05f)
        Brush.radialGradient(listOf(tint, Color.Transparent), center = center, radius = radius)
    }
    val vignette = Brush.radialGradient(
        0.55f to Color.Transparent, 1f to p.shade.copy(alpha = 0.16f),
        center = size.center, radius = max(size.width, size.height) * 0.75f,
    )
    val gutterWidth = 28.dp.toPx()
    val gutter = Brush.horizontalGradient(
        0f to p.shade.copy(alpha = 0.32f), 0.35f to p.shade.copy(alpha = 0.1f), 1f to Color.Transparent,
        endX = gutterWidth,
    )
    val fiberWidth = 0.6.dp.toPx()
    onDrawBehind {
        drawRect(p.paper)
        for (blotch in blotches) drawRect(blotch)
        for ((a, b, color) in fibers) drawLine(color, a, b, fiberWidth, StrokeCap.Round)
        for ((center, radius, color) in specks) drawCircle(color, radius, center)
        drawRect(vignette)
        drawRect(gutter, size = size.copy(width = gutterWidth))
    }
}

/** Planks of dark wood, under the book. */
fun Modifier.desk(p: BookPalette): Modifier = drawWithCache {
    val random = Random(11)
    val plank = 150.dp.toPx()
    val grain = mutableListOf<Pair<Path, Float>>()
    var y = 0f
    while (y < size.height) {
        val lines = 10 + random.nextInt(6)
        repeat(lines) {
            val base = y + random.nextFloat() * plank
            val amp = (1 + random.nextFloat() * 4).dp.toPx()
            val freq = (0.004f + random.nextFloat() * 0.01f)
            val phase = random.nextFloat() * 6f
            val path = Path().apply {
                moveTo(0f, base)
                var x = 0f
                while (x <= size.width) {
                    lineTo(x, base + amp * sin(x * freq + phase))
                    x += 6f
                }
            }
            grain += path to (0.08f + random.nextFloat() * 0.14f)
        }
        y += plank
    }
    val seams = generateSequence(plank) { it + plank }.takeWhile { it < size.height }.toList()
    val stroke = Stroke(1.dp.toPx())
    val seamStroke = 1.5.dp.toPx()
    onDrawBehind {
        drawRect(Brush.verticalGradient(listOf(p.deskTop, p.deskBottom)))
        for ((path, alpha) in grain) drawPath(path, Color.Black.copy(alpha = alpha), style = stroke)
        for (s in seams) {
            drawLine(Color.Black.copy(alpha = 0.45f), Offset(0f, s), Offset(size.width, s), seamStroke)
            drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, s + seamStroke), Offset(size.width, s + seamStroke), seamStroke)
        }
    }
}

/** A typographic flourish: two rules and a diamond. */
@Composable
fun Ornament(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val cy = size.height / 2
        val cx = size.width / 2
        val d = 4.dp.toPx()
        val reach = size.width * 0.3f
        val w = 0.8.dp.toPx()
        drawLine(color, Offset(cx - reach, cy), Offset(cx - d * 3, cy), w)
        drawLine(color, Offset(cx + d * 3, cy), Offset(cx + reach, cy), w)
        drawCircle(color, 1.5.dp.toPx(), Offset(cx - d * 2, cy))
        drawCircle(color, 1.5.dp.toPx(), Offset(cx + d * 2, cy))
        drawPath(Path().apply {
            moveTo(cx, cy - d); lineTo(cx + d, cy); lineTo(cx, cy + d); lineTo(cx - d, cy); close()
        }, color)
    }
}

/** Lays the content out turned a quarter clockwise (for text on the index tabs). */
fun Modifier.quarterTurn(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, maxWidth = constraints.maxHeight, minHeight = 0, maxHeight = constraints.maxWidth),
    )
    layout(placeable.height, placeable.width) {
        placeable.place((placeable.height - placeable.width) / 2, (placeable.width - placeable.height) / 2)
    }
}.rotate(90f)
