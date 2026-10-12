package app.gramcookbook.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    // An explicit factory: the default one is a process-wide singleton bound to the first Application.
    private val vm: AppViewModel by viewModels { ViewModelProvider.AndroidViewModelFactory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) vm.handleSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}

@Composable
fun App(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        vm.message = null
        snackbar.showSnackbar(m)
    }
    BackHandler(enabled = vm.screen != Screen.List) { vm.back() }
    when (val s = vm.screen) {
        // One branch, so the book stays open at its page while a recipe is shown.
        Screen.List, is Screen.Detail -> BookScreen(vm, snackbar)
        is Screen.Edit -> EditorScreen(vm, s.path, snackbar)
        is Screen.Add -> AddScreen(vm, s.fromLink, s.url, snackbar)
        Screen.Settings -> SettingsScreen(vm, snackbar)
    }
}
