package app.gramcookbook.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gramcookbook.R
import app.gramcookbook.data.RecipeStore

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (vm.configured) {
                        IconButton(onClick = { vm.sync(null) }, enabled = !vm.busy) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.sync))
                        }
                    }
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (vm.configured) {
                Box {
                    FloatingActionButton(onClick = { menu = true }) {
                        Icon(Icons.Default.Add, stringResource(R.string.add_recipe))
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
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!vm.configured) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.setup_hint), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { vm.navigate(Screen.Settings) }) { Text(stringResource(R.string.connect_repo)) }
                }
                return@Column
            }

            vm.syncProblem?.let { problem ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
            )
            val needle = RecipeStore.slug(query)
            val shown = remember(vm.recipes, needle) {
                if (needle.isEmpty()) vm.recipes
                else vm.recipes.filter { RecipeStore.slug(it.title + " " + it.path).contains(needle) }
            }
            if (shown.isEmpty()) {
                Text(
                    stringResource(if (vm.busy) R.string.syncing else R.string.no_recipes),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val uncategorized = stringResource(R.string.uncategorized)
            LazyColumn(Modifier.fillMaxSize()) {
                // Categories in alphabetical order, recipes without one last.
                val groups = shown.groupBy { it.category.lowercase() }
                    .toSortedMap(compareBy({ it.isEmpty() }, { it }))
                groups.forEach { (key, entries) ->
                    val label = entries.first().category.ifEmpty { uncategorized }
                    if (groups.size > 1 || key.isNotEmpty()) {
                        stickyHeader(key = "category:$key") {
                            Text(
                                label,
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    items(entries, key = { it.path }) { e ->
                        ListItem(
                            headlineContent = { Text(e.title) },
                            modifier = Modifier.clickable { vm.navigate(Screen.Detail(e.path)) },
                        )
                    }
                }
            }
        }
    }
}
