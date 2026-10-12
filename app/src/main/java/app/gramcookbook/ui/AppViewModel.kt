package app.gramcookbook.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gramcookbook.R
import app.gramcookbook.BuildConfig
import app.gramcookbook.data.ApkInstaller
import app.gramcookbook.data.GitConfig
import app.gramcookbook.data.Release
import app.gramcookbook.data.Updater
import app.gramcookbook.data.GitProblem
import app.gramcookbook.data.GitSync
import app.gramcookbook.data.ImportException
import app.gramcookbook.data.RecipeEntry
import app.gramcookbook.data.RecipeStore
import app.gramcookbook.data.Settings
import app.gramcookbook.data.SyncException
import app.gramcookbook.data.WebImporter
import app.gramcookbook.gram.GramParser
import app.gramcookbook.gram.GramWriter
import app.gramcookbook.gram.RecipeDraft
import kotlinx.coroutines.Dispatchers
import org.eclipse.jgit.storage.file.WindowCacheConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** Downloaded and verified; installing may still wait for the user's permission. */
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

sealed interface Screen {
    data object List : Screen
    data class Detail(val path: String) : Screen
    data class Edit(val path: String) : Screen
    data class Add(val fromLink: Boolean, val url: String = "") : Screen
    data object Settings : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val backStack = mutableStateListOf<Screen>(Screen.List)
    val screen: Screen get() = backStack.last()

    var recipes by mutableStateOf<List<RecipeEntry>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    /** Why the last sync failed; shown until a sync succeeds, since local changes are waiting. */
    var syncProblem by mutableStateOf<String?>(null)
        private set
    var configured by mutableStateOf(settings.isConfigured)
        private set

    /** Where checking for, downloading and installing a new version stands. */
    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** The page the book is open at, by key ("contents", "chapter:…", "recipe:<path>"), kept while editing. */
    var bookPage by mutableStateOf("contents")

    /** Servings chosen per recipe, kept while browsing. */
    val servings = mutableStateMapOf<String, Int>()

    private val gitLock = Mutex()
    val store: RecipeStore get() = RecipeStore(settings.repoDir())

    init {
        // JGit looks for ~/.gitconfig; Android has no home directory.
        System.setProperty("user.home", app.filesDir.absolutePath)
        // JGit's cache statistics use JMX (javax.management), which Android lacks.
        WindowCacheConfig().apply { setExposeStatsViaJmx(false) }.install()
        refresh()
        if (configured) sync(null)
        checkForUpdate(manual = false)
    }

    /** Asks GitHub for the latest release. Automatic checks stay silent when offline. */
    fun checkForUpdate(manual: Boolean) {
        if (update is UpdateState.Checking || update is UpdateState.Downloading) return
        viewModelScope.launch {
            update = UpdateState.Checking
            val result = runCatching { withContext(Dispatchers.IO) { Updater.latest() } }
            update = result.fold(
                onSuccess = { r ->
                    if (r != null && Updater.isNewer(r.version, BuildConfig.VERSION_NAME)) UpdateState.Available(r)
                    else UpdateState.UpToDate
                },
                onFailure = { e -> if (manual) UpdateState.Failed(str(R.string.update_check_failed, e.message ?: e.toString()), null) else UpdateState.Idle },
            )
        }
    }

    /** Downloads (once) and verifies the update, then opens Android's installer. */
    fun installUpdate() {
        val app = getApplication<Application>()
        when (val u = update) {
            is UpdateState.Ready -> startInstall(u.release, u.apk)
            is UpdateState.Available, is UpdateState.Failed -> {
                val release = (u as? UpdateState.Available)?.release ?: (u as UpdateState.Failed).release ?: return
                viewModelScope.launch {
                    update = UpdateState.Downloading(release, 0f)
                    val apk = File(File(app.cacheDir, "updates"), "GramCookbook-${release.version}.apk")
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            Updater.download(release, apk) { p ->
                                viewModelScope.launch { if (update is UpdateState.Downloading) update = UpdateState.Downloading(release, p) }
                            }
                        }
                    }
                    result.onSuccess { startInstall(release, it) }.onFailure { e ->
                        val reason = if (e.message == "checksum" || e.message == "size") str(R.string.update_corrupt) else e.message ?: e.toString()
                        update = UpdateState.Failed(str(R.string.update_download_failed, reason), release)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun startInstall(release: Release, apk: File) {
        val app = getApplication<Application>()
        if (ApkInstaller.compareSignature(app, apk) == ApkInstaller.Signature.DIFFERENT) {
            update = UpdateState.Failed(str(R.string.update_other_key), null)
            return
        }
        update = UpdateState.Ready(release, apk)
        if (!ApkInstaller.canInstall(app)) {
            // Android asks once; the user comes back and taps Install again.
            message = str(R.string.update_allow_install)
            ApkInstaller.requestPermission(app)
            return
        }
        ApkInstaller.install(app, apk)
    }

    fun navigate(s: Screen) {
        backStack += s
    }

    /** Replaces the current screen, e.g. the editor by the saved recipe. */
    fun replace(s: Screen) {
        backStack[backStack.lastIndex] = s
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun refresh() {
        viewModelScope.launch {
            recipes = withContext(Dispatchers.IO) { store.list() }
        }
    }

    private fun str(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    /** Commits local changes, pulls and pushes. [commitMessage] null = nothing new expected. */
    fun sync(commitMessage: String?) {
        if (!configured) return
        viewModelScope.launch {
            busy = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    gitLock.withLock {
                        GitSync(settings.repoDir(), settings.git).sync(commitMessage ?: "Update recipes")
                    }
                }
            }
            busy = false
            result.onFailure { syncProblem = syncError(it) }.onSuccess { syncProblem = null }
            if (commitMessage == null) result.onSuccess { changed -> if (changed) message = str(R.string.synced) }
            refresh()
        }
    }

    private fun syncError(e: Throwable): String = problemText(GitProblem.of(e), e.message.orEmpty())

    private fun problemText(problem: GitProblem, detail: String): String = when (problem) {
        GitProblem.CONFLICT -> str(R.string.error_conflict)
        GitProblem.NO_TOKEN -> str(R.string.error_no_token)
        GitProblem.AUTH -> str(R.string.error_auth)
        GitProblem.FORBIDDEN -> str(R.string.error_forbidden)
        GitProblem.NOT_FOUND -> str(R.string.error_not_found)
        GitProblem.NETWORK -> str(R.string.error_network)
        GitProblem.OTHER -> str(R.string.sync_failed, detail)
    }

    /** Tests [config] (as typed, not yet saved). The result is a sentence and whether all is well. */
    fun checkAccess(config: GitConfig, onResult: (String, Boolean) -> Unit) {
        viewModelScope.launch {
            val check = withContext(Dispatchers.IO) {
                GitSync(settings.repoDir(), config).checkAccess()
            }
            val text = when {
                check.canWrite -> str(R.string.access_ok, check.defaultBranch ?: "?")
                check.canRead -> str(R.string.access_read_only, problemText(check.problem ?: GitProblem.OTHER, check.detail.orEmpty()))
                else -> problemText(check.problem ?: GitProblem.OTHER, check.detail.orEmpty())
            }
            onResult(text, check.canWrite)
        }
    }

    fun saveSettings(config: GitConfig) {
        settings.git = config
        configured = settings.isConfigured
        syncProblem = null
        recipes = emptyList()
        backStack.clear()
        backStack += Screen.List
        sync(null)
    }

    fun read(path: String): String = store.read(path)

    /** Saves raw Gram text from the editor. */
    fun saveText(path: String, text: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.write(path, text) }
            val title = GramParser.readTitle(text) ?: path
            // Back to the recipe the editor was opened from, rather than a second copy of it.
            if (backStack.getOrNull(backStack.lastIndex - 1) == Screen.Detail(path)) back() else replace(Screen.Detail(path))
            sync(str(R.string.commit_edit, title))
        }
    }

    /** Converts a plain recipe to Gram, saves it, and shows it. */
    fun saveDraft(draft: RecipeDraft) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                val p = store.newPath(draft.title)
                store.write(p, GramWriter.toGram(draft))
                p
            }
            replace(Screen.Detail(path))
            sync(str(R.string.commit_add, draft.title.ifBlank { path }))
        }
    }

    /** Existing categories, for suggestions. */
    val categories: List<String>
        get() = recipes.map { it.category }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /** Writes `category:` in the recipe's frontmatter (removes it when blank) and syncs. */
    fun setCategory(path: String, category: String) {
        viewModelScope.launch {
            val title = withContext(Dispatchers.IO) {
                val text = store.read(path)
                store.write(path, GramWriter.setMeta(text, "category", category))
                GramParser.readTitle(text) ?: path
            }
            refresh()
            sync(str(R.string.commit_category, title, category.ifBlank { "—" }))
        }
    }

    fun delete(path: String) {
        viewModelScope.launch {
            val title = withContext(Dispatchers.IO) {
                val t = runCatching { GramParser.readTitle(store.read(path)) }.getOrNull() ?: path
                store.delete(path)
                t
            }
            backStack.clear()
            backStack += Screen.List
            sync(str(R.string.commit_delete, title))
        }
    }

    fun importUrl(url: String, onResult: (RecipeDraft) -> Unit) {
        viewModelScope.launch {
            busy = true
            val result = runCatching { withContext(Dispatchers.IO) { WebImporter.fetch(url) } }
            busy = false
            result.onSuccess(onResult).onFailure { e ->
                message = when {
                    e is ImportException && e.message == "no-recipe" -> str(R.string.error_no_recipe)
                    else -> str(R.string.error_import, e.message ?: e.toString())
                }
            }
        }
    }

    /** A link shared from the browser opens the import form directly. */
    fun handleSharedText(text: String?) {
        val url = text?.let { Regex("""https?://\S+""").find(it)?.value } ?: return
        if (!configured) {
            message = str(R.string.setup_first)
            return
        }
        navigate(Screen.Add(fromLink = true, url = url))
    }
}
