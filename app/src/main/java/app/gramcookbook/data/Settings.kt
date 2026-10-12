package app.gramcookbook.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Repository settings, kept in the app's private preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val reposDir = File(context.filesDir, "repos")
    private val sourcesDir = File(context.filesDir, "sources")

    /** The recipes when git sync is off: plain files, no repository. */
    val localDir = File(context.filesDir, "recipes")

    /** Off by default; installs that already had a repository (before this setting existed) keep syncing. */
    var syncEnabled: Boolean
        get() = prefs.getBoolean("syncEnabled", git.url.isNotBlank())
        set(value) {
            prefs.edit().putBoolean("syncEnabled", value).apply()
        }

    var git: GitConfig
        get() = GitConfig(
            url = prefs.getString("url", "").orEmpty(),
            branch = prefs.getString("branch", "").orEmpty(),
            username = prefs.getString("username", "").orEmpty(),
            token = prefs.getString("token", "").orEmpty(),
            authorName = prefs.getString("authorName", "").orEmpty(),
            authorEmail = prefs.getString("authorEmail", "").orEmpty(),
        )
        set(c) {
            prefs.edit()
                .putString("url", c.url.trim())
                .putString("branch", c.branch.trim())
                .putString("username", c.username.trim())
                .putString("token", c.token.trim())
                .putString("authorName", c.authorName.trim())
                .putString("authorEmail", c.authorEmail.trim())
                .apply()
        }

    /** Sync is on and has a repository to sync with. */
    val isConfigured: Boolean get() = syncEnabled && git.url.isNotBlank()

    /** Read-only repositories whose recipes join the book. */
    var sources: List<Source>
        get() {
            val json = JSONArray(prefs.getString("sources", "[]"))
            return List(json.length()) { i -> json.getJSONObject(i).let { Source(it.getString("url"), it.optString("token")) } }
        }
        set(list) {
            val json = JSONArray(list.map { JSONObject().put("url", it.url.trim()).put("token", it.token.trim()) })
            prefs.edit().putString("sources", json.toString()).apply()
        }

    fun sourceDir(source: Source) = File(sourcesDir, source.id)

    /** One working copy per repository URL, so changing the URL never mixes two repositories. */
    fun repoDir(): File {
        val url = git.url
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(reposDir, hash.take(12))
    }
}
