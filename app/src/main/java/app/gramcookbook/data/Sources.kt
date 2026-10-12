package app.gramcookbook.data

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.security.MessageDigest

/** Someone else's recipe repository, read alongside the cookbook's own recipes: fetched, never written to. */
data class Source(val url: String, val token: String = "") {
    /** Prefix of its recipes' keys (`<id>:breakfast/pancakes.gram`), and the name of its folder. */
    val id: String get() = sha1(url.trim()).take(12)

    /** A short name for the page headers: `alice/recipes` for `https://github.com/alice/recipes.git`. */
    val name: String
        get() = url.trim().removeSuffix("/").removeSuffix(".git").split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")

    companion object {
        fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Keeps [dir] an exact copy of [source]'s default branch: clone once, then fetch and reset. */
class SourceSync(private val dir: File, private val source: Source) {
    private val credentials =
        source.token.takeIf { it.isNotBlank() }?.let { UsernamePasswordCredentialsProvider("git", it.trim()) }

    /** Returns true when the recipes changed. */
    fun update(): Boolean {
        if (!File(dir, ".git").isDirectory) {
            val tmp = File(dir.parentFile, dir.name + ".clone")
            tmp.deleteRecursively()
            try {
                Git.cloneRepository().setURI(source.url.trim()).setDirectory(tmp).setCredentialsProvider(credentials).call().close()
            } catch (e: Exception) {
                tmp.deleteRecursively()
                throw SyncException(e.message ?: e.toString(), e)
            }
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw SyncException("Could not move the clone into place")
            return true
        }
        Git.open(dir).use { git ->
            val before = git.repository.resolve("HEAD")
            git.fetch().setCredentialsProvider(credentials).setRemoveDeletedRefs(true).call()
            val remote = "refs/remotes/origin/${git.repository.branch}"
            git.repository.findRef(remote) ?: return false
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remote).call()
            return git.repository.resolve("HEAD") != before
        }
    }
}
