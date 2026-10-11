package app.gramcookbook.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Repository settings, kept in the app's private preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val reposDir = File(context.filesDir, "repos")

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

    val isConfigured: Boolean get() = git.url.isNotBlank()

    /** One working copy per repository URL, so changing the URL never mixes two repositories. */
    fun repoDir(): File {
        val url = git.url
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(reposDir, hash.take(12))
    }
}
