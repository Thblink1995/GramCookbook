package app.gramcookbook

import app.gramcookbook.data.GitConfig
import app.gramcookbook.data.GitProblem
import app.gramcookbook.data.GitSync
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

class AccessCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun check(url: String, token: String = "") =
        GitSync(tmp.newFolder(), GitConfig(url = url, token = token)).checkAccess()

    @Test fun localRepositoryIsReadableAndWritable() {
        val seed = tmp.newFolder("seed")
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            File(seed, "a.gram").writeText("x")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("first").setAuthor("t", "t@t").setCommitter("t", "t@t").call()
        }
        val bare = File(tmp.root, "remote.git")
        Git.cloneRepository().setURI(seed.toURI().toString()).setDirectory(bare).setBare(true).call().close()

        val ok = check(bare.toURI().toString())
        assertTrue(ok.canRead)
        assertTrue(ok.canWrite)
        assertEquals("main", ok.defaultBranch)
        // Nothing was written.
        Git.open(bare).use { git -> assertEquals(listOf("first"), git.log().call().map { it.shortMessage }) }
    }

    @Test fun missingRepository() {
        val r = check(File(tmp.root, "nope.git").toURI().toString())
        assertFalse(r.canRead)
        assertEquals(GitProblem.NOT_FOUND, r.problem)
    }

    /** An HTTP server answering every request with [status]. */
    private fun server(status: String): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    it.getOutputStream().write(
                        "HTTP/1.1 $status\r\nWWW-Authenticate: Basic realm=\"git\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                }
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    private fun httpCheck(status: String, token: String = "") = server(status).use { s ->
        check("http://127.0.0.1:${s.localPort}/me/recipes.git", token)
    }

    @Test fun httpStatusesAreExplained() {
        assertEquals(GitProblem.NO_TOKEN, httpCheck("401 Unauthorized").problem)
        assertEquals(GitProblem.AUTH, httpCheck("401 Unauthorized", token = "wrong").problem)
        assertEquals(GitProblem.FORBIDDEN, httpCheck("403 Forbidden", token = "read-only").problem)
        assertEquals(GitProblem.NOT_FOUND, httpCheck("404 Not Found").problem)
        assertEquals(GitProblem.NETWORK, check("https://recipes.invalid/me/recipes.git").problem)
    }
}
