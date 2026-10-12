package app.gramcookbook.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gramcookbook.R
import app.gramcookbook.data.GitConfig
import app.gramcookbook.data.Source
import app.gramcookbook.gram.RecipeDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormScaffold(
    vm: AppViewModel,
    title: String,
    snackbar: SnackbarHostState,
    actions: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = { actions() },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        content(Modifier.padding(padding).fillMaxSize().imePadding())
    }
}

/** Raw Gram editor, with buttons for the symbols that are awkward on a phone keyboard. */
@Composable
fun EditorScreen(vm: AppViewModel, path: String, snackbar: SnackbarHostState) {
    var value by rememberSaveable(path, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var loaded by rememberSaveable(path) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        if (!loaded) {
            value = TextFieldValue(withContext(Dispatchers.IO) { runCatching { vm.read(path) }.getOrDefault("") })
            loaded = true
        }
    }

    fun insert(token: String, cursorBack: Int = 0) {
        val sel = value.selection
        val text = value.text.replaceRange(sel.min, sel.max, token)
        value = TextFieldValue(text, TextRange(sel.min + token.length - cursorBack))
    }

    FormScaffold(vm, stringResource(R.string.edit), snackbar, actions = {
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        IconButton(onClick = { vm.saveText(path, value.text) }, enabled = loaded) { Icon(Icons.Default.Check, stringResource(R.string.save)) }
    }) { modifier ->
        Column(modifier) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = { insert("@{}", 2) }, label = { Text("@ " + stringResource(R.string.ingredient)) })
                AssistChip(onClick = { insert("#{}", 2) }, label = { Text("# " + stringResource(R.string.utensil)) })
                AssistChip(onClick = { insert("~{}", 1) }, label = { Text("~ " + stringResource(R.string.timer)) })
                AssistChip(onClick = { insert("~_{}", 1) }, label = { Text("~_ " + stringResource(R.string.rest)) })
                AssistChip(onClick = { insert("^{}", 1) }, label = { Text("^ °C") })
                AssistChip(onClick = { insert("\n## ") }, label = { Text("## Section") })
            }
            GramTextField(value, { value = it }, Modifier.fillMaxSize())
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(path) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The Gram source editor, highlighted like the playground. */
@Composable
private fun GramTextField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, modifier: Modifier) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
        visualTransformation = GramVisualTransformation,
    )
}

/** The editor alone, for screenshots. */
@Composable
internal fun EditorPreview(source: String) {
    GramTextField(TextFieldValue(source), {}, Modifier.fillMaxSize())
}

/** New recipe in plain language, typed or filled from a web page, converted to Gram on save. */
@Composable
fun AddScreen(vm: AppViewModel, fromLink: Boolean, initialUrl: String, snackbar: SnackbarHostState) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var title by rememberSaveable { mutableStateOf("") }
    var portions by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var ingredients by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var fetched by rememberSaveable { mutableStateOf(false) }

    val fill: (RecipeDraft) -> Unit = { d ->
        title = d.title
        portions = d.portions
        ingredients = d.ingredients
        steps = d.steps
        source = d.source
        author = d.author
        if (d.category.isNotBlank()) category = d.category
        fetched = true
    }
    LaunchedEffect(Unit) {
        if (fromLink && initialUrl.isNotBlank() && !fetched) vm.importUrl(initialUrl, fill)
    }

    FormScaffold(vm, stringResource(if (fromLink) R.string.from_link else R.string.write_recipe), snackbar, actions = {
        IconButton(
            onClick = { vm.saveDraft(RecipeDraft(title, portions, ingredients, steps, category, source, author)) },
            enabled = title.isNotBlank() && !vm.busy,
        ) { Icon(Icons.Default.Check, stringResource(R.string.save)) }
    }) { modifier ->
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (fromLink) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.link)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Button(onClick = { vm.importUrl(url, fill) }, enabled = url.isNotBlank() && !vm.busy) {
                        Text(stringResource(R.string.fetch))
                    }
                }
                if (!fetched) Text(stringResource(R.string.link_hint), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.title)) }, singleLine = true)
            OutlinedTextField(
                portions, { v -> portions = v.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.servings)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            CategoryField(category, { category = it }, vm.categories)
            OutlinedTextField(
                ingredients, { ingredients = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ingredients)) },
                supportingText = { Text(stringResource(R.string.ingredients_hint)) },
                minLines = 5,
            )
            OutlinedTextField(
                steps, { steps = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.steps)) },
                supportingText = { Text(stringResource(R.string.steps_hint)) },
                minLines = 6,
            )
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val current = remember { vm.settings.git }
    var syncOn by rememberSaveable { mutableStateOf(vm.settings.syncEnabled) }
    var url by rememberSaveable { mutableStateOf(current.url) }
    var branch by rememberSaveable { mutableStateOf(current.branch) }
    var username by rememberSaveable { mutableStateOf(current.username) }
    var token by rememberSaveable { mutableStateOf(current.token) }
    var name by rememberSaveable { mutableStateOf(current.authorName) }
    var email by rememberSaveable { mutableStateOf(current.authorEmail) }
    // Result of "Test access": the sentence, and whether reading and writing both work.
    var check by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val typed = GitConfig(url, branch, username, token, name, email)
    val validUrl = url.startsWith("https://") || url.startsWith("http://") || url.startsWith("file:/")

    FormScaffold(vm, stringResource(R.string.settings), snackbar, actions = {}) { modifier ->
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().toggleable(syncOn, role = Role.Switch, onValueChange = { syncOn = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.git_sync), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = syncOn, onCheckedChange = null)
            }
            Text(stringResource(R.string.git_sync_off_hint), style = MaterialTheme.typography.bodySmall)
            if (syncOn) {
                Text(stringResource(R.string.settings_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.repo_url)) },
                    placeholder = { Text("https://github.com/you/recipes.git") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(branch, { branch = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.branch)) }, singleLine = true)
                OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.username)) }, singleLine = true)
                OutlinedTextField(
                    token, { token = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.token)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedButton(
                    onClick = {
                        checking = true
                        check = null
                        vm.checkAccess(typed) { text, ok -> check = text to ok; checking = false }
                    },
                    enabled = validUrl && !checking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (checking) R.string.checking else R.string.test_access)) }
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                check?.let { (text, ok) ->
                    Text(
                        (if (ok) "✓ " else "✗ ") + text,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.author_name)) }, singleLine = true)
                OutlinedTextField(
                    email, { email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.author_email)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
            }
            Button(
                onClick = { vm.saveSettings(syncOn, typed) },
                enabled = !syncOn || validUrl,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (syncOn) R.string.save_and_sync else R.string.save)) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SourcesSection(vm)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            UpdateSection(vm)
        }
    }
}

/** Read-only repositories: the ones in the book, and a form to add one (kept only if it can be cloned). */
@Composable
private fun SourcesSection(vm: AppViewModel) {
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val validUrl = url.startsWith("https://") || url.startsWith("http://") || url.startsWith("file:/")

    Text(stringResource(R.string.sources_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.sources_hint), style = MaterialTheme.typography.bodySmall)
    for (source in vm.sources) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(source.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = { vm.removeSource(source) }) {
                Icon(Icons.Default.Delete, stringResource(R.string.remove_source, source.name))
            }
        }
    }
    OutlinedTextField(
        url, { url = it; error = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.source_url)) },
        placeholder = { Text("https://github.com/someone/recipes.git") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedTextField(
        token, { token = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.source_token)) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    OutlinedButton(
        onClick = {
            adding = true
            error = null
            vm.addSource(Source(url.trim(), token.trim())) { e ->
                adding = false
                error = e
                if (e == null) { url = ""; token = "" }
            }
        },
        enabled = validUrl && !adding,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (adding) R.string.adding else R.string.add_source)) }
    if (adding) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text("✗ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

