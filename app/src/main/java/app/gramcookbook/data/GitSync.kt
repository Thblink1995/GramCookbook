package app.gramcookbook.data

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import java.net.ConnectException
import java.nio.file.Files
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

data class GitConfig(
    val url: String,
    val branch: String = "",
    val username: String = "",
    val token: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
)

class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What went wrong talking to the remote, from JGit's exceptions. */
enum class GitProblem { CONFLICT, NO_TOKEN, AUTH, FORBIDDEN, NOT_FOUND, NETWORK, OTHER;

    companion object {
        fun of(e: Throwable): GitProblem {
            val chain = generateSequence(e) { it.cause }.toList()
            val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
            return when {
                e.message == "conflict" -> CONFLICT
                chain.any { it is NoRemoteRepositoryException } || "not found" in text -> NOT_FOUND
                // A public repository is readable anonymously; writing asks for credentials.
                "no credentialsprovider" in text -> NO_TOKEN
                "not authorized" in text -> AUTH
                "not permitted" in text || "403" in text || "forbidden" in text || "denied" in text -> FORBIDDEN
                chain.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException } -> NETWORK
                else -> OTHER
            }
        }
    }
}

/** Result of [GitSync.checkAccess]; [problem] tells why the first failing step failed. */
data class AccessCheck(val canRead: Boolean, val canWrite: Boolean, val defaultBranch: String?, val problem: GitProblem?, val detail: String?)

/**
 * Keeps [dir] in sync with the remote: commit everything, pull with rebase, push.
 * HTTPS only; with GitHub the token goes in the password field.
 */
class GitSync(private val dir: File, private val config: GitConfig) {
    private val credentials: CredentialsProvider? =
        if (config.token.isBlank()) null
        else UsernamePasswordCredentialsProvider(config.username.ifBlank { "git" }, config.token)

    val isCloned: Boolean get() = File(dir, ".git").isDirectory

    fun cloneIfNeeded() {
        if (isCloned) return
        // Clone next to the target, then carry over recipes written before the first clone.
        val tmp = File(dir.parentFile, dir.name + ".clone")
        tmp.deleteRecursively()
        try {
            Git.cloneRepository()
                .setURI(config.url.trim())
                .setDirectory(tmp)
                .setCredentialsProvider(credentials)
                .apply { if (config.branch.isNotBlank()) setBranch(config.branch.trim()) }
                .call()
                .close()
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw SyncException(e.message ?: e.toString(), e)
        }
        if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val target = File(tmp, f.relativeTo(dir).path)
                if (!target.exists()) f.copyTo(target)
            }
            dir.deleteRecursively()
        }
        if (!tmp.renameTo(dir)) throw SyncException("Could not move the clone into place")
    }

    /** Returns true when something changed locally or remotely. */
    fun sync(message: String): Boolean {
        cloneIfNeeded()
        Git.open(dir).use { git ->
            try {
                disableAutoGc(git)
                val committed = commitAll(git, message)
                val head = git.repository.resolve("HEAD")

                val hasRemoteBranch = git.repository.config.getString("branch", git.repository.branch, "merge") != null
                if (hasRemoteBranch) {
                    val pull = git.pull().setRebase(true).setCredentialsProvider(credentials).call()
                    val rebase = pull.rebaseResult
                    if (rebase != null && !rebase.status.isSuccessful) {
                        if (rebase.status == RebaseResult.Status.STOPPED || rebase.status == RebaseResult.Status.CONFLICTS) {
                            git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
                        }
                        throw SyncException("conflict")
                    }
                    if (!pull.isSuccessful) throw SyncException("pull-failed")
                }
                val pulled = git.repository.resolve("HEAD") != head

                val results = git.push().setCredentialsProvider(credentials).call()
                for (r in results) for (u in r.remoteUpdates) {
                    if (u.status != RemoteRefUpdate.Status.OK && u.status != RemoteRefUpdate.Status.UP_TO_DATE) {
                        throw SyncException("push-${u.status.name.lowercase()}${u.message?.let { ": $it" }.orEmpty()}")
                    }
                }
                return committed || pulled
            } catch (e: SyncException) {
                throw e
            } catch (e: Exception) {
                throw SyncException(e.message ?: e.toString(), e)
            }
        }
    }

    /**
     * JGit runs an automatic GC after fetches and rebases, and its GC lock reads the process
     * id through java.lang.management, which Android lacks. A recipe repository stays small,
     * so it simply never collects.
     */
    private fun disableAutoGc(git: Git) {
        val config = git.repository.config
        if (config.getInt("gc", "auto", -1) == 0 && config.getInt("gc", "autopacklimit", -1) == 0) return
        config.setInt("gc", null, "auto", 0)
        config.setInt("gc", null, "autopacklimit", 0)
        config.save()
    }

    /**
     * Checks the URL and token without changing anything: reading lists the remote branches,
     * writing opens a push connection and closes it without sending. Hosts like GitHub only
     * accept that connection with credentials allowed to write.
     */
    fun checkAccess(): AccessCheck {
        val uri = try {
            URIish(config.url.trim())
        } catch (e: Exception) {
            return AccessCheck(false, false, null, GitProblem.NOT_FOUND, e.message)
        }
        // Transports need a local repository: an empty throwaway one.
        val scratch = Files.createTempDirectory("access-check").toFile()
        try {
            Git.init().setBare(true).setDirectory(scratch).call().use { git ->
                fun open() = Transport.open(git.repository, uri).also { it.credentialsProvider = credentials }
                val branch = try {
                    open().use { t -> t.openFetch().use { c -> c.getRef("HEAD")?.target?.name?.removePrefix("refs/heads/") } }
                } catch (e: Exception) {
                    return AccessCheck(false, false, null, GitProblem.of(e), e.message)
                }
                try {
                    open().use { t -> t.openPush().close() }
                } catch (e: Exception) {
                    return AccessCheck(true, false, branch, GitProblem.of(e), e.message)
                }
                return AccessCheck(true, true, branch, null, null)
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun commitAll(git: Git, message: String): Boolean {
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        if (git.status().call().isClean) return false
        val name = config.authorName.ifBlank { "Gram Cookbook" }
        val email = config.authorEmail.ifBlank { "cookbook@localhost" }
        git.commit().setMessage(message).setAuthor(PersonIdent(name, email)).setCommitter(PersonIdent(name, email)).call()
        return true
    }
}
