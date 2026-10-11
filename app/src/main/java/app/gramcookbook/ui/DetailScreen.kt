package app.gramcookbook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.gramcookbook.R
import app.gramcookbook.gram.GramParser
import app.gramcookbook.gram.Part
import app.gramcookbook.gram.Quantity
import app.gramcookbook.gram.Recipe
import app.gramcookbook.gram.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: AppViewModel, path: String, snackbar: SnackbarHostState) {
    // Re-read after each sync (vm.recipes changes), in case the file was updated remotely.
    var recipe by remember(path) { mutableStateOf<Recipe?>(null) }
    LaunchedEffect(path, vm.recipes) {
        recipe = withContext(Dispatchers.IO) { runCatching { GramParser.parse(vm.read(path)) }.getOrNull() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.Edit(path)) }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val r = recipe
        if (r == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            var editCategory by remember { mutableStateOf(false) }
            val category = r.meta["category"]?.trim().orEmpty()
            if (editCategory) {
                CategoryDialog(category, vm.categories, onDismiss = { editCategory = false }) {
                    editCategory = false
                    if (it != category) vm.setCategory(path, it)
                }
            }
            RecipeView(r, category, { editCategory = true }, vm.servings[path], { vm.servings[path] = it }, PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()))
        }
    }
}

@Composable
private fun RecipeView(recipe: Recipe, category: String, onCategoryClick: () -> Unit, chosen: Int?, onChoose: (Int) -> Unit, padding: PaddingValues) {
    val base = recipe.portions
    // Without `portions`, the stepper counts halves of the recipe: 2 = ×1.
    val count = chosen ?: base ?: 2
    val factor = if (base != null) count.toDouble() / base else count / 2.0
    val ingredients = remember(recipe) { recipe.ingredientList() }
    val cookware = remember(recipe) { recipe.cookware() }
    val uri = LocalUriHandler.current
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val st = stepStyles()

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistChip(
                    onClick = onCategoryClick,
                    label = { Text(category.ifEmpty { stringResource(R.string.add_category) }) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                )
                recipe.meta["makes"]?.takeIf { it.isNotBlank() }?.let { Text(it, color = muted) }
                recipe.meta["author"]?.let(GramParser::listValues)?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), color = muted) }
                GramParser.listValues(recipe.meta["source"]).firstOrNull()?.let { src ->
                    Text(
                        stringResource(R.string.source),
                        color = accent,
                        modifier = Modifier.padding(vertical = 4.dp)
                            .clickable(enabled = src.startsWith("http")) { runCatching { uri.openUri(src) } },
                    )
                }
                recipe.meta["notes"]?.takeIf { it.isNotBlank() }?.let { Text(it, fontStyle = FontStyle.Italic) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = { onChoose(count - 1) }, enabled = count > 1) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Text(
                    if (base != null) pluralStringResource(R.plurals.portions, count, count)
                    else "×" + Quantity.formatNumber(factor, ""),
                    style = MaterialTheme.typography.titleMedium,
                )
                FilledTonalIconButton(onClick = { onChoose(count + 1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
        }
        if (ingredients.isNotEmpty()) {
            item { Heading(stringResource(R.string.ingredients)) }
            for (item in ingredients) {
                item {
                    Text(buildAnnotatedString {
                        withStyle(st.ingredient) { append(item.name) }
                        item.format(factor).takeIf { it.isNotEmpty() }?.let { pill(st, it) }
                        if (item.optional) withStyle(st.note) { append(" (${st.optional})") }
                    })
                }
            }
        }
        if (cookware.isNotEmpty()) {
            item {
                val label = stringResource(R.string.equipment_list, "\u0000")
                Text(
                    buildAnnotatedString {
                        append(label.substringBefore('\u0000'))
                        cookware.forEachIndexed { i, c ->
                            if (i > 0) append(", ")
                            withStyle(st.cookware) { append(c) }
                        }
                        append(label.substringAfter('\u0000'))
                    },
                    color = muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        var number = 0
        for (section in recipe.sections) {
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            section.title?.takeIf { it.isNotEmpty() }?.let { t -> item { Heading(t) } }
            for (step in section.steps) {
                if (step.isCommentOnly) {
                    item { Text(stepText(step, factor, st), fontStyle = FontStyle.Italic, color = muted) }
                } else {
                    number++
                    val n = number
                    item {
                        Row {
                            Text("$n", Modifier.width(28.dp), color = accent, fontWeight = FontWeight.Bold)
                            Text(stepText(step, factor, st), Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** Styles of the Gram renderer's HTML preview (gram.css), as spans. */
private class StepStyles(val colors: RecipeColors, val muted: Color, val strong: Color, val pill: Color, val optional: String) {
    val ingredient = SpanStyle(color = colors.ingredient, fontWeight = FontWeight.SemiBold)
    val cookware = SpanStyle(color = colors.cookware, fontWeight = FontWeight.SemiBold)
    val timer = SpanStyle(color = colors.timer, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 0.9.em)
    val temperature = SpanStyle(color = colors.temperature, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 0.9.em)
    val intermediate = SpanStyle(color = colors.intermediate, fontWeight = FontWeight.SemiBold)
    val quantity = SpanStyle(color = strong, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 0.85.em, background = pill)
    val note = SpanStyle(color = muted, fontStyle = FontStyle.Italic, fontSize = 0.95.em)
    val action = SpanStyle(color = strong, fontWeight = FontWeight.Bold, fontSize = 0.8.em, background = pill, letterSpacing = 0.05.em)
}

@Composable
private fun stepStyles() = StepStyles(
    colors = recipeColors(),
    muted = MaterialTheme.colorScheme.onSurfaceVariant,
    strong = MaterialTheme.colorScheme.onSurface,
    pill = MaterialTheme.colorScheme.surfaceVariant,
    optional = stringResource(R.string.optional),
)

/** A quantity "pill"; thin spaces stand in for the padding. */
private fun AnnotatedString.Builder.pill(st: StepStyles, text: String) {
    append(' ')
    withStyle(st.quantity) { append("\u2009" + text + "\u2009") }
}

private fun stepText(step: Step, factor: Double, st: StepStyles): AnnotatedString = buildAnnotatedString {
    step.action?.let {
        withStyle(st.action) { append("\u2009" + it.uppercase() + "\u2009") }
        append("  ")
    }
    for (p in step.parts) when (p) {
        is Part.Text -> append(p.text)
        is Part.Ingredient -> {
            withStyle(st.ingredient) { append(p.display) }
            p.quantity?.takeUnless { it.isEmpty }?.let { q -> pill(st, (if (p.isFixed) q else q.scaled(factor)).format()) }
            p.preparation?.let { withStyle(st.note) { append(" ($it)") } }
            if (p.isOptional) withStyle(st.note) { append(" (${st.optional})") }
        }
        is Part.Cookware -> withStyle(st.cookware) { append(p.display) }
        is Part.Timer -> withStyle(st.timer) { append((if (p.passive) "⏳ " else "⏱ ") + p.value) }
        is Part.Temperature -> withStyle(st.temperature) { append(p.value) }
        is Part.Reference -> {
            withStyle(st.intermediate) { append("▸ " + p.name) }
            p.quantity?.takeUnless { it.isEmpty }?.let { q -> pill(st, q.scaled(factor).format()) }
        }
        is Part.Output -> withStyle(st.intermediate.copy(fontSize = 0.9.em)) { append(" → " + p.name) }
        is Part.Comment -> withStyle(st.note) { append(" " + p.text + " ") }
    }
}

/** The recipe view alone, for screenshots. */
@Composable
internal fun RecipePreview(source: String) {
    val recipe = remember(source) { GramParser.parse(source) }
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeView(recipe, recipe.meta["category"].orEmpty(), {}, null, {}, PaddingValues())
    }
}
