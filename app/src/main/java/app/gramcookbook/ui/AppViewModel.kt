package app.gramcookbook.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
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
import app.gramcookbook.data.Source
import app.gramcookbook.data.SourceSync
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
    /** Running syncs, updates and imports; they can overlap. */
    private var tasks by mutableIntStateOf(0)
    val busy: Boolean get() = tasks > 0
    var message by mutableStateOf<String?>(null)

    /** Why the last sync failed; shown until a sync succeeds, since local changes are waiting. */
    var syncProblem by mutableStateOf<String?>(null)
        private set
    /** Git sync is on and has a repository; otherwise the recipes are plain local files. */
    var configured by mutableStateOf(settings.isConfigured)
        private set

    /** Read-only repositories whose recipes join the book. */
    var sources by mutableStateOf(settings.sources)
        private set

    /** Where checking for, downloading and installing a new version stands. */
    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** The page the book is open at, by key ("contents", "chapter:…", "recipe:<path>"), kept while editing. */
    var bookPage by mutableStateOf("contents")

    /** Servings chosen per recipe, kept while browsing. */
    val servings = mutableStateMapOf<String, Int>()

    private val gitLock = Mutex()
    private val sourcesLock = Mutex()
    val store: RecipeStore get() = RecipeStore(if (configured) settings.repoDir() else settings.localDir)
    private val localStore get() = RecipeStore(settings.localDir)

    init {
        // JGit looks for ~/.gitconfig; Android has no home directory.
        System.setProperty("user.home", app.filesDir.absolutePath)
        // JGit's cache statistics use JMX (javax.management), which Android lacks.
        WindowCacheConfig().apply { setExposeStatsViaJmx(false) }.install()
        refresh()
        if (configured) sync(null)
        pullSources(manual = false)
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
            recipes = withContext(Dispatchers.IO) {
                val others = sources.flatMap { source ->
                    RecipeStore(settings.sourceDir(source)).list().map { it.copy(path = "${source.id}:${it.path}", source = source.name) }
                }
                (store.list() + others).sortedBy { it.title.lowercase() }
            }
        }
    }

    /** The read-only repository a recipe comes from, or null for the cookbook's own. */
    fun sourceOf(path: String): Source? = sources.firstOrNull { path.startsWith(it.id + ":") }

    /** Brings the read-only repositories up to date; automatic updates stay silent when they fail. */
    fun pullSources(manual: Boolean) {
        val list = sources
        if (list.isEmpty()) return
        viewModelScope.launch {
            tasks++
            val failures = withContext(Dispatchers.IO) {
                sourcesLock.withLock {
                    list.mapNotNull { source ->
                        runCatching { SourceSync(settings.sourceDir(source), source).update() }.exceptionOrNull()?.let { source to it }
                    }
                }
            }
            tasks--
            if (manual) failures.firstOrNull()?.let { (source, e) -> message = str(R.string.source_failed, source.name, syncError(e)) }
            refresh()
        }
    }

    /** The ⟳ button: the cookbook's own repository, then the read-only ones. */
    fun syncAll() {
        sync(null)
        pullSources(manual = true)
    }

    /** Clones [source]; it is kept only if that works. [onResult] gets an error, or null once added. */
    fun addSource(source: Source, onResult: (String?) -> Unit) {
        if (sources.any { it.id == source.id }) return onResult(str(R.string.source_exists))
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { sourcesLock.withLock { SourceSync(settings.sourceDir(source), source).update() } }
            }
            result.onSuccess {
                sources = sources + source
                settings.sources = sources
                refresh()
            }
            onResult(result.exceptionOrNull()?.let(::syncError))
        }
    }

    fun removeSource(source: Source) {
        sources = sources.filter { it.id != source.id }
        settings.sources = sources
        if (bookPage.startsWith("recipe:${source.id}:")) bookPage = "contents"
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sourcesLock.withLock { settings.sourceDir(source).deleteRecursively() } }
            refresh()
        }
    }

    /** Copies a read-only recipe into the cookbook's own, where it can be changed, and shows the copy. */
    fun copyToMine(path: String) {
        viewModelScope.launch {
            val (copy, title) = withContext(Dispatchers.IO) {
                val text = read(path)
                val title = GramParser.readTitle(text) ?: path.substringAfterLast('/').removeSuffix(".gram")
                val p = store.newPath(title)
                store.write(p, text)
                p to title
            }
            replace(Screen.Detail(copy))
            sync(str(R.string.commit_add, title))
        }
    }

    private fun str(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    /** Commits local changes, pulls and pushes. [commitMessage] null = nothing new expected. */
    fun sync(commitMessage: String?) {
        if (!configured) return refresh()
        viewModelScope.launch {
            tasks++
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    gitLock.withLock {
                        val git = GitSync(settings.repoDir(), settings.git)
                        git.cloneIfNeeded()
                        // Recipes written while sync was off join the repository (and leave the local folder).
                        val moved = localStore.copyTo(RecipeStore(settings.repoDir()), move = true)
                        git.sync(commitMessage ?: if (moved > 0) "Add recipes from the phone" else "Update recipes")
                    }
                }
            }
            tasks--
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

    fun saveSettings(enabled: Boolean, config: GitConfig) {
        // Turning sync off keeps the book as it was: the repository's recipes become the local ones.
        val leaving = if (configured) RecipeStore(settings.repoDir()) else null
        settings.git = config
        settings.syncEnabled = enabled
        configured = settings.isConfigured
        syncProblem = null
        recipes = emptyList()
        backStack.clear()
        backStack += Screen.List
        if (leaving != null && !configured) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { gitLock.withLock { leaving.copyTo(localStore) } }
                refresh()
            }
        } else {
            sync(null)
        }
    }

    fun read(path: String): String {
        val source = sourceOf(path) ?: return store.read(path)
        return RecipeStore(settings.sourceDir(source)).read(path.removePrefix(source.id + ":"))
    }

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

    /** Saves a new recipe written directly in Gram (named after its title), and shows it. */
    fun saveGram(text: String) {
        val title = GramParser.readTitle(text) ?: return
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { store.newPath(title).also { store.write(it, text) } }
            replace(Screen.Detail(path))
            sync(str(R.string.commit_add, title))
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
            tasks++
            val result = runCatching { withContext(Dispatchers.IO) { WebImporter.fetch(url) } }
            tasks--
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
        navigate(Screen.Add(fromLink = true, url = url))
    }
}
