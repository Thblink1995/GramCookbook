package app.gramcookbook.ui

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.gramcookbook.R
import app.gramcookbook.data.RecipeEntry
import app.gramcookbook.data.RecipeStore
import app.gramcookbook.gram.GramParser
import app.gramcookbook.gram.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** A page of the book: the contents, a chapter's title page (one per category), or a recipe. */
private sealed interface Leaf {
    val key: String

    data object Contents : Leaf {
        override val key get() = "contents"
    }

    data class Chapter(val number: Int, val label: String, val category: String, val color: Color, val entries: List<RecipeEntry>) : Leaf {
        override val key get() = "chapter:$category"
    }

    data class RecipeLeaf(val entry: RecipeEntry, val chapter: Chapter) : Leaf {
        override val key get() = "recipe:${entry.path}"
    }
}

/** Contents first, then each category in alphabetical order (uncategorized last), its title page and its recipes. */
private fun bind(recipes: List<RecipeEntry>, uncategorized: String, palette: BookPalette): List<Leaf> {
    val leaves = mutableListOf<Leaf>(Leaf.Contents)
    val groups = recipes.groupBy { it.category.lowercase() }.toSortedMap(compareBy({ it.isEmpty() }, { it }))
    var number = 0
    for ((category, entries) in groups) {
        number++
        val color = if (category.isEmpty()) palette.tabNone else palette.tabs[(number - 1) % palette.tabs.size]
        val chapter = Leaf.Chapter(number, entries.first().category.ifEmpty { uncategorized }, category, color, entries)
        leaves += chapter
        entries.mapTo(leaves) { Leaf.RecipeLeaf(it, chapter) }
    }
    return leaves
}

private fun roman(n: Int): String {
    var rest = n
    return buildString {
        for ((value, digits) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
            while (rest >= value) { append(digits); rest -= value }
        }
    }
}

/** A page being turned: [top] lies on [under]; [progress] goes from 0 (flat) to 1 (turned over, [under] shown). */
private class Turn(val top: String, val under: String)

@Stable
private class TurnState {
    var turn by mutableStateOf<Turn?>(null)
    var progress by mutableFloatStateOf(0f)
    var animating by mutableStateOf(false)
    /** A page asked for while another turn was under way, by key: turned to once the book is still. */
    var pending by mutableStateOf<String?>(null)
}

/** The recipes as a book: swipe to turn pages, tabs for the chapters, contents with search. */
@Composable
fun BookScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val palette = LocalBookPalette.current
    val uncategorized = stringResource(R.string.uncategorized)
    val leaves = remember(vm.recipes, uncategorized, palette) { bind(vm.recipes, uncategorized, palette) }
    val byKey = remember(leaves) { leaves.associateBy { it.key } }
    val indexOf = remember(leaves) { leaves.withIndex().associate { (i, leaf) -> leaf.key to i } }
    val page = indexOf[vm.bookPage] ?: 0
    val state = remember { TurnState() }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val recipes = remember { mutableStateMapOf<String, Recipe>() }

    // The book lies on a dark desk: light status bar icons while it is shown.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val light = bars?.isAppearanceLightStatusBars
        val lightNav = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            light?.let { bars?.isAppearanceLightStatusBars = it }
            lightNav?.let { bars?.isAppearanceLightNavigationBars = it }
        }
    }

    suspend fun load(path: String) {
        if (path in recipes) return
        withContext(Dispatchers.IO) { runCatching { GramParser.parse(vm.read(path)) }.getOrNull() }?.let { recipes[path] = it }
    }

    /** Keeps the screen (and so the back stack) in step with the page the book rests on. */
    fun settled(key: String) {
        vm.bookPage = key
        val leaf = byKey[key]
        val screen = vm.screen
        if (leaf is Leaf.RecipeLeaf) {
            val detail = Screen.Detail(leaf.entry.path)
            if (screen is Screen.Detail) { if (screen != detail) vm.replace(detail) } else vm.navigate(detail)
        } else if (screen is Screen.Detail) {
            vm.back()
        }
    }

    fun finish() {
        val t = state.turn ?: return
        val key = if (state.progress >= 0.5f) t.under else t.top
        state.turn = null
        state.animating = false
        settled(key)
    }

    // Pages keep the click handlers of the composition that drew them: read the book as it is now, not as it was then.
    val currentLeaves by rememberUpdatedState(leaves)
    val currentIndexOf by rememberUpdatedState(indexOf)

    fun turnTo(target: Int) {
        val leaves = currentLeaves
        val page = currentIndexOf[vm.bookPage] ?: 0
        if (target !in leaves.indices) return
        if (state.animating || state.turn != null) {
            state.pending = leaves[target].key
            return
        }
        if (target == page) return
        state.animating = true
        scope.launch {
            (leaves[target] as? Leaf.RecipeLeaf)?.let { load(it.entry.path) }
            val forward = target > page
            state.turn = if (forward) Turn(leaves[page].key, leaves[target].key) else Turn(leaves[target].key, leaves[page].key)
            val from = if (forward) 0f else 1f
            state.progress = from
            animate(from, 1f - from, animationSpec = tween(560, easing = FastOutSlowInEasing)) { v, _ -> state.progress = v }
            finish()
        }
    }

    // Re-read loaded recipes after each sync, in case they changed remotely.
    LaunchedEffect(vm.recipes) {
        val paths = vm.recipes.mapTo(HashSet()) { it.path }
        for (path in recipes.keys.toList()) {
            if (path !in paths) recipes.remove(path)
            else withContext(Dispatchers.IO) { runCatching { GramParser.parse(vm.read(path)) }.getOrNull() }?.let { recipes[path] = it }
        }
    }
    // The neighbors are ready before the page is turned.
    LaunchedEffect(page, leaves) {
        for (i in listOf(page, page + 1, page - 1)) (leaves.getOrNull(i) as? Leaf.RecipeLeaf)?.let { load(it.entry.path) }
    }
    // Opening a recipe from elsewhere (a new recipe, back from the editor) turns to it; leaving it, back to the contents.
    val detailPath = (vm.screen as? Screen.Detail)?.path
    LaunchedEffect(detailPath, indexOf) {
        if (detailPath != null) indexOf["recipe:$detailPath"]?.let(::turnTo)
        else if (leaves.getOrNull(page) is Leaf.RecipeLeaf) turnTo(0)
    }
    LaunchedEffect(state.pending, state.animating, state.turn) {
        val key = state.pending ?: return@LaunchedEffect
        if (state.animating || state.turn != null) return@LaunchedEffect
        state.pending = null
        indexOf[key]?.let(::turnTo)
    }
    BackHandler(enabled = vm.screen == Screen.List && page != 0) { turnTo(0) }

    Box(Modifier.fillMaxSize().desk(palette)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            DeskHeader(vm)
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, bottom = 14.dp, top = 4.dp)) {
                IndexTabs(leaves, page, onTab = ::turnTo, modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, bottom = 28.dp))
                Cover(palette, Modifier.fillMaxSize().padding(end = 24.dp)) {
                    Pages(
                        leaves, byKey, page, state,
                        onDragEnd = { velocity ->
                            if (state.turn != null && !state.animating) {
                                state.animating = true
                                val target = when {
                                    velocity < -1_000f -> 1f
                                    velocity > 1_000f -> 0f
                                    else -> if (state.progress > 0.5f) 1f else 0f
                                }
                                animate(state.progress, target, animationSpec = tween(320, easing = FastOutSlowInEasing)) { v, _ -> state.progress = v }
                                finish()
                            }
                        },
                    ) { leaf, index ->
                        when (leaf) {
                            Leaf.Contents -> ContentsPage(vm, leaves, indexOf, query, { query = it }, ::turnTo)
                            is Leaf.Chapter -> ChapterPage(leaf, indexOf, ::turnTo)
                            is Leaf.RecipeLeaf -> RecipePage(vm, leaf.entry.path, leaf.chapter.label, leaf.entry.source, recipes, ::load)
                        }
                        Folio(index + 1, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        WaxSeal(vm, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 6.dp, bottom = 2.dp))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun DeskHeader(vm: AppViewModel) {
    val palette = LocalBookPalette.current
    Column {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.app_name),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = palette.gold,
                letterSpacing = 2.sp,
            )
            if (vm.configured || vm.sources.isNotEmpty()) {
                IconButton(onClick = { vm.syncAll() }, enabled = !vm.busy) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.sync), tint = palette.gold.copy(alpha = if (vm.busy) 0.4f else 1f))
                }
            }
            IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = palette.gold)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 18.dp)) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxSize(), color = palette.gold, trackColor = Color.Transparent)
        }
    }
}

/** The leather cover, its stitching, and the edges of the pages under the one shown. */
@Composable
private fun Cover(palette: BookPalette, modifier: Modifier, pages: @Composable () -> Unit) {
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 10.dp, bottomEnd = 10.dp)
    Box(
        modifier
            .shadow(16.dp, shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(palette.leather, palette.leatherDark)))
            .drawBehind {
                val inset = 4.dp.toPx()
                drawRoundRect(
                    palette.gold.copy(alpha = 0.45f),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
                // The spine.
                val spine = 16.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(listOf(palette.leatherDark, palette.leather, palette.leatherDark), endX = spine),
                    size = Size(spine, size.height),
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 16.dp, top = 9.dp, end = 9.dp, bottom = 12.dp)
                .drawBehind {
                    val step = 1.5.dp.toPx()
                    for (i in 4 downTo 1) {
                        val o = Offset(step * i, step * i)
                        drawRect(lerp(palette.paperEdge, palette.shade, 0.08f * i), topLeft = o, size = size)
                        drawRect(palette.shade.copy(alpha = 0.25f), topLeft = o, size = size, style = Stroke(0.5.dp.toPx()))
                    }
                },
        ) { pages() }
    }
}

/** The page the book is open at; while turning, the page under it and the fold. */
@Composable
private fun Pages(
    leaves: List<Leaf>,
    byKey: Map<String, Leaf>,
    page: Int,
    state: TurnState,
    onDragEnd: suspend (Float) -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.(Leaf, Int) -> Unit,
) {
    val palette = LocalBookPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val drag = rememberDraggableState { delta ->
            if (state.animating) return@rememberDraggableState
            if (state.turn == null) {
                state.turn = when {
                    delta < 0 && page < leaves.lastIndex -> Turn(leaves[page].key, leaves[page + 1].key).also { state.progress = 0f }
                    delta > 0 && page > 0 -> Turn(leaves[page - 1].key, leaves[page].key).also { state.progress = 1f }
                    else -> return@rememberDraggableState
                }
            }
            state.progress = (state.progress - delta / width).coerceIn(0f, 1f)
        }
        val turn = state.turn
        val shown = if (turn == null) listOfNotNull(leaves.getOrNull(page)?.key) else listOf(turn.under, turn.top)
        Box(
            Modifier.fillMaxSize().draggable(
                drag, Orientation.Horizontal,
                enabled = leaves.size > 1 && !state.animating,
                onDragStopped = { velocity -> onDragEnd(velocity) },
            ),
        ) {
            for (k in shown) key(k) {
                val leaf = byKey[k]
                val layer = when {
                    turn == null -> Modifier
                    k == turn.top -> Modifier.drawWithContent { drawTopPage(state.progress, palette) }
                    else -> Modifier.drawWithContent { drawUnderPage(state.progress, palette) }
                }
                Box(Modifier.fillMaxSize().then(layer).paper(palette)) {
                    if (leaf != null) content(leaf, leaves.indexOf(leaf))
                }
            }
        }
    }
}

/** The turning page: what is still flat, then its back folded over it, casting a shadow. */
private fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.drawTopPage(p: Float, palette: BookPalette) {
    val w = size.width
    val fold = w * (1 - p)
    val edge = w * (1 - 2 * p)
    clipRect(right = fold) { this@drawTopPage.drawContent() }
    if (p <= 0f || p >= 1f) return
    val shadow = (6 + 28 * (1 - p)).dp.toPx()
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, palette.shade.copy(alpha = 0.3f)), startX = edge - shadow, endX = edge),
        topLeft = Offset(edge - shadow, 0f), size = Size(shadow, size.height),
    )
    val back = palette.paperBack
    drawRect(
        Brush.horizontalGradient(
            0f to lerp(back, palette.shade, 0.18f),
            0.12f to back,
            0.65f to lerp(back, Color.White, 0.12f),
            1f to lerp(back, palette.shade, 0.22f),
            startX = edge, endX = fold,
        ),
        topLeft = Offset(edge, 0f), size = Size(fold - edge, size.height),
    )
    drawLine(palette.shade.copy(alpha = 0.35f), Offset(edge, 0f), Offset(edge, size.height), 1.dp.toPx())
}

/** The page being uncovered, darker in the shadow of the fold. */
private fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.drawUnderPage(p: Float, palette: BookPalette) {
    drawContent()
    if (p <= 0f || p >= 1f) return
    val fold = size.width * (1 - p)
    val shadow = (10 + 40 * (1 - p)).dp.toPx()
    drawRect(palette.shade.copy(alpha = 0.12f * (1 - p)), topLeft = Offset(fold, 0f), size = Size(size.width - fold, size.height))
    drawRect(
        Brush.horizontalGradient(listOf(palette.shade.copy(alpha = 0.4f * (1 - p) + 0.05f), Color.Transparent), startX = fold, endX = fold + shadow),
        topLeft = Offset(fold, 0f), size = Size(shadow, size.height),
    )
}

@Composable
private fun Folio(number: Int, modifier: Modifier) {
    Text(
        "— $number —",
        modifier.padding(bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = LocalBookPalette.current.inkMuted,
    )
}

/** Tabs along the edge of the pages, one per chapter, after the contents. */
@Composable
private fun IndexTabs(leaves: List<Leaf>, page: Int, onTab: (Int) -> Unit, modifier: Modifier) {
    val palette = LocalBookPalette.current
    val current = when (val leaf = leaves.getOrNull(page)) {
        is Leaf.Chapter -> leaf
        is Leaf.RecipeLeaf -> leaf.chapter
        else -> null
    }
    Column(modifier.width(38.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IndexTab(stringResource(R.string.contents), palette.leather, active = page == 0) { onTab(0) }
        leaves.forEachIndexed { i, leaf ->
            if (leaf is Leaf.Chapter) IndexTab(leaf.label, leaf.color, active = leaf == current) { onTab(i) }
        }
    }
}

@Composable
private fun IndexTab(label: String, color: Color, active: Boolean, onSelect: () -> Unit) {
    val palette = LocalBookPalette.current
    val shape = RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
    Box(
        Modifier
            .width(if (active) 38.dp else 31.dp)
            .height(88.dp)
            .shadow(if (active) 4.dp else 2.dp, shape)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(lerp(color, Color.Black, 0.35f), color, lerp(color, Color.White, if (active) 0.12f else 0f))))
            .clickable(onClick = onSelect)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Tab
                selected = active
                onClick { onSelect(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            Modifier.padding(start = 10.dp).quarterTurn().padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = palette.tabInk.copy(alpha = if (active) 1f else 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Add a recipe", as a wax seal pressed on the corner of the book. */
@Composable
private fun WaxSeal(vm: AppViewModel, modifier: Modifier) {
    val palette = LocalBookPalette.current
    var menu by remember { mutableStateOf(false) }
    val description = stringResource(R.string.add_recipe)
    Box(modifier) {
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .clickable { menu = true }
                .semantics { contentDescription = description; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2 * 0.94f
                val blob = Path().apply {
                    val points = 28
                    for (i in 0..points) {
                        val a = i * 2 * Math.PI / points
                        val rr = r * (0.93f + 0.07f * sin(i * 2.7f) * cos(i * 1.3f))
                        val pt = Offset(center.x + rr * cos(a).toFloat(), center.y + rr * sin(a).toFloat())
                        if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                    }
                    close()
                }
                drawPath(blob, Brush.radialGradient(listOf(lerp(palette.seal, Color.White, 0.15f), palette.seal, palette.sealDark), center = center - Offset(r * 0.25f, r * 0.25f), radius = r * 1.4f))
                drawCircle(palette.sealDark.copy(alpha = 0.7f), r * 0.68f, style = Stroke(2.dp.toPx()))
                drawCircle(lerp(palette.seal, Color.White, 0.25f).copy(alpha = 0.5f), r * 0.68f, center = center + Offset(-1f, -1f), style = Stroke(0.8.dp.toPx()))
            }
            Icon(Icons.Default.Add, null, tint = lerp(palette.seal, Color.White, 0.55f), modifier = Modifier.size(28.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.write_recipe)) },
                onClick = { menu = false; vm.navigate(Screen.Add(fromLink = false)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.from_link)) },
                onClick = { menu = false; vm.navigate(Screen.Add(fromLink = true)) },
            )
        }
    }
}

/** Room under the last line of a page for the folio and the wax seal. */
private val PageEnd = 84.dp
private val PageGutter = 26.dp

@Composable
private fun ContentsPage(
    vm: AppViewModel,
    leaves: List<Leaf>,
    indexOf: Map<String, Int>,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val palette = LocalBookPalette.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PageGutter, end = 18.dp, top = 24.dp, bottom = PageEnd),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = palette.ink, textAlign = TextAlign.Center)
                if (vm.recipes.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.recipe_count, vm.recipes.size, vm.recipes.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkMuted,
                    )
                }
                Ornament(palette.inkMuted, Modifier.padding(top = 10.dp))
            }
        }
        item { UpdateBanner(vm) }
        if (vm.recipes.isEmpty() && !vm.configured && !vm.busy) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.setup_hint), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Button(onClick = { vm.navigate(Screen.Settings) }) { Text(stringResource(R.string.connect_repo)) }
                }
            }
            return@LazyColumn
        }
        vm.syncProblem?.let { problem -> item { SyncNote(vm, problem) } }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(4.dp),
            )
        }
        val needle = RecipeStore.slug(query)
        var any = false
        for (leaf in leaves) {
            if (leaf !is Leaf.Chapter) continue
            val shown = if (needle.isEmpty()) leaf.entries else leaf.entries.filter { RecipeStore.slug(it.title + " " + it.path).contains(needle) }
            if (shown.isEmpty()) continue
            any = true
            item(key = leaf.key) { ChapterHeading(leaf) { indexOf[leaf.key]?.let(onOpen) } }
            items(shown, key = { it.path }) { e ->
                TocLine(e.title, e.source, (indexOf["recipe:${e.path}"] ?: 0) + 1) { indexOf["recipe:${e.path}"]?.let(onOpen) }
            }
        }
        if (!any) {
            item {
                Text(
                    stringResource(if (vm.busy) R.string.syncing else R.string.no_recipes),
                    Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkMuted,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                )
            }
        }
    }
}

/** A note pinned to the contents while local changes wait to be sent. */
@Composable
private fun SyncNote(vm: AppViewModel, problem: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(2.dp),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.not_synced), style = MaterialTheme.typography.titleSmall)
            Text(problem, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = { vm.navigate(Screen.Settings) }) { Text(stringResource(R.string.settings)) }
                TextButton(onClick = { vm.sync(null) }, enabled = !vm.busy) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@Composable
private fun ChapterHeading(chapter: Leaf.Chapter, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(roman(chapter.number), style = MaterialTheme.typography.titleMedium, color = chapter.color)
        Text(chapter.label, style = MaterialTheme.typography.titleMedium, color = LocalBookPalette.current.ink)
    }
}

/** "Title ........ 12": the title, a dotted leader, and the page number. */
@Composable
private fun TocLine(title: String, source: String?, page: Int, onClick: () -> Unit) {
    val palette = LocalBookPalette.current
    // A recipe from a read-only repository names it after the title, as a smaller credit.
    val text = buildAnnotatedString {
        append(title)
        if (source != null) withStyle(SpanStyle(color = palette.inkMuted, fontSize = 13.sp, fontStyle = FontStyle.Italic)) { append("  $source") }
    }
    Layout(
        content = {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = palette.ink)
            Canvas(Modifier) {
                val step = 5.dp.toPx()
                var x = step / 2
                while (x < size.width) {
                    drawCircle(palette.inkMuted, 0.9.dp.toPx(), Offset(x, size.height / 2))
                    x += step
                }
            }
            Text("$page", style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val number = measurables[2].measure(Constraints())
        val text = measurables[0].measure(Constraints(maxWidth = (constraints.maxWidth - number.width - gap * 4).coerceAtLeast(0)))
        val baseline = text[LastBaseline]
        val dotsHeight = 4.dp.roundToPx()
        val dotsWidth = (constraints.maxWidth - text.width - number.width - gap * 2).coerceAtLeast(0)
        val dots = measurables[1].measure(Constraints.fixed(dotsWidth, dotsHeight))
        val numberY = baseline - number[FirstBaseline]
        layout(constraints.maxWidth, maxOf(text.height, numberY + number.height)) {
            text.place(0, 0)
            dots.place(text.width + gap, baseline - dotsHeight)
            number.place(constraints.maxWidth - number.width, numberY)
        }
    }
}

@Composable
private fun ChapterPage(chapter: Leaf.Chapter, indexOf: Map<String, Int>, onOpen: (Int) -> Unit) {
    val palette = LocalBookPalette.current
    Box(Modifier.fillMaxWidth().height(6.dp).background(chapter.color.copy(alpha = 0.85f)))
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PageGutter, end = 18.dp, top = 56.dp, bottom = PageEnd),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.chapter, roman(chapter.number)).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = chapter.color,
                    letterSpacing = 4.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(chapter.label, style = MaterialTheme.typography.displaySmall, color = palette.ink, textAlign = TextAlign.Center)
                Ornament(chapter.color, Modifier.padding(vertical = 12.dp))
                Text(
                    pluralStringResource(R.plurals.recipe_count, chapter.entries.size, chapter.entries.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkMuted,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                )
            }
        }
        items(chapter.entries, key = { it.path }) { e ->
            TocLine(e.title, e.source, (indexOf["recipe:${e.path}"] ?: 0) + 1) { indexOf["recipe:${e.path}"]?.let(onOpen) }
        }
    }
}

@Composable
private fun RecipePage(
    vm: AppViewModel,
    path: String,
    chapter: String,
    source: String?,
    recipes: SnapshotStateMap<String, Recipe>,
    load: suspend (String) -> Unit,
) {
    val palette = LocalBookPalette.current
    LaunchedEffect(path) { load(path) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = palette.inkMuted) }
            // The running head: the chapter, as printed at the top of a book's pages.
            Text(
                // A recipe from a read-only repository also names it.
                listOfNotNull(chapter, source).joinToString(" · ").uppercase(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = palette.inkMuted,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source == null) {
                IconButton(onClick = { vm.navigate(Screen.Edit(path)) }) { Icon(Icons.Default.Edit, stringResource(R.string.edit), tint = palette.inkMuted) }
            } else {
                TextButton(onClick = { vm.copyToMine(path) }) { Text(stringResource(R.string.copy_to_mine), color = palette.inkMuted) }
            }
        }
        val recipe = recipes[path]
        if (recipe == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = palette.inkMuted) }
        } else {
            RecipeContent(vm, path, recipe, PaddingValues(start = PageGutter, end = 18.dp, top = 0.dp, bottom = PageEnd))
        }
    }
}
