package app.gramcookbook

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.gramcookbook.ui.MainActivity
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import app.gramcookbook.data.Updater
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The whole flow on the real screens: connect, browse, scale, write a recipe, sync. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
// Real text measuring and clipping (in the legacy graphics mode, taps inside the book's rounded cover are lost),
// on a phone-sized screen.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class AppUiTest {
    @get:Rule(order = 0) val tmp = TemporaryFolder()

    // Each test starts from a fresh install, before the activity is launched.
    @get:Rule(order = 1) val freshApp = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Context>()
            app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
            File(app.filesDir, "repos").deleteRecursively()
            File(app.filesDir, "recipes").deleteRecursively()
            File(app.filesDir, "sources").deleteRecursively()
            File(app.cacheDir, "updates").deleteRecursively()
            // The automatic update check at start must not reach GitHub from tests.
            Updater.latestReleaseUrl = "http://127.0.0.1:9/none"
        }
    }

    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    private fun remote(): File {
        val seed = tmp.newFolder("seed")
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            File(seed, "breakfast").mkdirs()
            File(seed, "breakfast/pancakes.gram").writeText(
                "---\ntitle: 'Pancakes'\ncategory: 'Petit-déjeuner'\nportions: 2\n---\n\n[Mix] @flour{100g} with @=salt{1 pinch} and @eggs{2} in a #bowl.\n\n[Cook] ~{2min} per side.\n",
            )
            git.add().addFilepattern(".").call()
            git.commit().setMessage("first").setAuthor("t", "t@t").setCommitter("t", "t@t").call()
        }
        val bare = File(tmp.root, "remote.git")
        Git.cloneRepository().setURI(seed.toURI().toString()).setDirectory(bare).setBare(true).call().close()
        return bare
    }

    /** Ingredient with its quantity pill, as rendered (thin spaces pad the pill). */
    private fun item(name: String, qty: String) = "$name \u2009$qty\u2009"

    // The same ingredient and quantity can appear in the list and in a step.
    private fun assertShown(text: String) =
        compose.onAllNodesWithText(text, substring = true).onFirst().assertExists()

    private fun waitForText(text: String, substring: Boolean = false) {
        try {
            compose.waitUntilAtLeastOneExists(hasText(text, substring = substring), 20_000)
        } catch (e: Throwable) {
            throw AssertionError("'$text' not shown. Screen:\n" + compose.onRoot().printToString(), e)
        }
    }

    @Test fun connectBrowseScaleAndAdd() {
        val remote = remote()
        waitForText("Connect a repository")
        compose.onNodeWithText("Connect a repository").performClick()
        compose.onNodeWithText("Sync with a git repository").performClick()
        compose.onNode(hasText("Repository URL (HTTPS)") and hasSetTextAction()).performScrollTo().performTextInput(remote.toURI().toString())
        compose.onNodeWithText("Test access").performScrollTo().performClick()
        waitForText("✓ Read and write allowed (default branch: main).")
        compose.onNode(hasText("Commit author name") and hasSetTextAction()).performScrollTo().performTextInput("Phone")
        compose.onNodeWithText("Save and sync").performScrollTo().performClick()

        // Clone, then browse.
        waitForText("Pancakes")
        compose.onNodeWithText("Petit-déjeuner").assertExists()
        compose.onNodeWithText("Pancakes").performClick()
        waitForText("2 servings")
        assertShown(item("flour", "100 g"))
        assertShown(item("eggs", "2"))

        // 2 -> 3 servings: scaled, except the fixed salt.
        compose.onNodeWithText("+").performClick()
        waitForText("3 servings")
        assertShown(item("flour", "150 g"))
        assertShown(item("eggs", "3"))
        assertShown(item("salt", "1 pinch"))
        assertShown(item("flour", "150 g"))

        // Write a new recipe in plain language.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Add a recipe").performClick()
        compose.onNodeWithText("Write a recipe").performClick()
        compose.onNode(hasText("Title") and hasSetTextAction()).performScrollTo().performTextInput("Crêpes")
        compose.onNode(hasText("Servings") and hasSetTextAction()).performScrollTo().performTextInput("4")
        compose.onNode(hasText("Category") and hasSetTextAction()).performScrollTo().performTextInput("Dessert")
        compose.onNode(hasText("Ingredients") and hasSetTextAction()).performScrollTo().performTextInput("250 g de farine\n4 œufs\n50 cl de lait")
        compose.onNode(hasText("Steps") and hasSetTextAction()).performScrollTo().performTextInput("Mélanger la farine et les œufs.\nAjouter le lait.")
        compose.onNodeWithContentDescription("Save").performClick()

        waitForText("4 servings")
        assertShown(item("farine", "250 g"))
        compose.onNodeWithText("+").performClick()
        waitForText("5 servings")
        assertShown(item("farine", "313 g"))
        assertShown(item("lait", "63 cl"))

        // The new recipe reaches the remote.
        compose.waitUntil(20_000) {
            Git.open(remote).use { git -> git.log().call().first().shortMessage == "Add Crêpes" }
        }
        Git.open(remote).use { git ->
            val head = git.log().call().first()
            assertEquals("Phone", head.authorIdent.name)
        }
        val clone = File(tmp.root, "check")
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(clone).call().close()
        val crepes = File(clone, "crepes.gram").readText()
        assertTrue(crepes, crepes.contains("category: 'Dessert'"))
        assertTrue(crepes, crepes.contains("la @farine{250g} et les @œufs{4}"))

        // Edit it as Gram text and see the change.
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(crepes.replace("@farine{250g}", "@farine{300g}"))
        compose.onNodeWithContentDescription("Save").performClick()
        waitForText(item("farine", "375 g"), substring = true)

        // Both categories in the list; then move the crêpes to the existing one from their page.
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Dessert")
        waitForText("Petit-déjeuner")
        compose.onNodeWithText("Crêpes").performClick()
        waitForText("5 servings")
        compose.onNodeWithText("Dessert").performClick()
        compose.onNodeWithText("Petit-déjeuner").performClick()
        compose.onNodeWithText("OK").performClick()
        waitForText("Petit-déjeuner")
        try {
            compose.waitUntil(20_000) {
                Git.open(remote).use { git -> git.log().call().first().shortMessage == "Category of Crêpes: Petit-déjeuner" }
            }
        } catch (e: Throwable) {
            val log = Git.open(remote).use { git -> git.log().call().map { it.shortMessage } }
            throw AssertionError("remote log: $log\nScreen:\n" + compose.onRoot().printToString(), e)
        }
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Petit-déjeuner")
        compose.onNodeWithText("Dessert").assertDoesNotExist()
    }

    @Test fun recipesWithoutGitJoinTheRepositoryWhenSyncIsTurnedOn() {
        val remote = remote()
        waitForText("Connect a repository")

        // Sync is off on a fresh install: write a recipe on the phone only.
        compose.onNodeWithContentDescription("Add a recipe").performClick()
        compose.onNodeWithText("Write a recipe").performClick()
        compose.onNode(hasText("Title") and hasSetTextAction()).performScrollTo().performTextInput("Soupe")
        compose.onNode(hasText("Ingredients") and hasSetTextAction()).performScrollTo().performTextInput("2 poireaux")
        compose.onNode(hasText("Steps") and hasSetTextAction()).performScrollTo().performTextInput("Couper les poireaux.")
        compose.onNodeWithContentDescription("Save").performClick()
        waitForText(item("poireaux", "2"), substring = true)
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Soupe")

        // Turn sync on: the phone's recipe joins the repository's.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Sync with a git repository").performClick()
        compose.onNode(hasText("Repository URL (HTTPS)") and hasSetTextAction()).performScrollTo().performTextInput(remote.toURI().toString())
        compose.onNodeWithText("Save and sync").performScrollTo().performClick()
        waitForText("Pancakes")
        waitForText("Soupe")
        compose.waitUntil(20_000) {
            Git.open(remote).use { git -> git.log().call().first().shortMessage == "Add recipes from the phone" }
        }

        // Turn it off again: the book keeps both, as plain files.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Sync with a git repository").performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        waitForText("Pancakes")
        waitForText("Soupe")
        compose.onNodeWithContentDescription("Sync").assertDoesNotExist()
    }

    @Test fun readOnlyRepositoryJoinsTheBookAndCanBeCopied() {
        val other = remote()
        waitForText("Connect a repository")

        // Add someone else's repository: its recipe joins the book, credited to it.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasText("URL of a cookbook to read (HTTPS)") and hasSetTextAction()).performScrollTo().performTextInput(other.toURI().toString())
        compose.onNodeWithText("Add").performScrollTo().performClick()
        val name = "${other.parentFile!!.name}/remote"
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Remove $name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Pancakes", substring = true)
        compose.onNodeWithText("Pancakes", substring = true).performClick()
        waitForText("2 servings")
        compose.onNodeWithContentDescription("Edit").assertDoesNotExist()

        // A copy is the cookbook's own: editable, and a plain file on the phone (sync is off).
        compose.onNodeWithText("Copy to my cookbook").performClick()
        waitForText("2 servings")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty() }
        val app = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(File(app.filesDir, "recipes/pancakes.gram").readText().contains("@flour{100g}"))
    }

    @Test fun importFromLink() {
        val remote = remote()
        val page = """<html><script type="application/ld+json">{"@type":"Recipe","name":"Tarte aux pommes",
            "recipeYield":"6 parts","recipeIngredient":["1 pâte brisée","4 pommes","30 g de beurre"],
            "recipeInstructions":[{"@type":"HowToStep","text":"Étaler la pâte."},{"@type":"HowToStep","text":"Disposer les pommes et le beurre."}]}</script></html>"""
        // Minimal HTTP server: answers every request with the page.
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val body = page.toByteArray()
                    val out = it.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    out.flush()
                }
            }
        }.apply { isDaemon = true }.start()
        try {
            waitForText("Connect a repository")
            compose.onNodeWithText("Connect a repository").performClick()
            compose.onNodeWithText("Sync with a git repository").performClick()
            compose.onNode(hasText("Repository URL (HTTPS)") and hasSetTextAction()).performScrollTo().performTextInput(remote.toURI().toString())
            compose.onNodeWithText("Save and sync").performScrollTo().performClick()
            waitForText("Pancakes")

            compose.onNodeWithContentDescription("Add a recipe").performClick()
            compose.onNodeWithText("From a link").performClick()
            compose.onNode(hasText("Recipe link") and hasSetTextAction()).performTextInput("http://127.0.0.1:${server.localPort}/tarte")
            compose.onNodeWithText("Fetch").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Tarte aux pommes") and hasSetTextAction(), 20_000)
            compose.onNode(hasText("6") and hasSetTextAction()).assertExists()
            compose.onNodeWithContentDescription("Save").performClick()

            waitForText("6 servings")
            assertShown(item("beurre", "30 g"))
            compose.onNodeWithText("+").performClick()
            waitForText(item("beurre", "35 g"), substring = true)
            compose.waitUntil(20_000) {
                Git.open(remote).use { git -> git.log().call().first().shortMessage == "Add Tarte aux pommes" }
            }
        } finally {
            server.close()
        }
    }

    @Test fun failedPushIsShownAndRetried() {
        val remote = remote()
        waitForText("Connect a repository")
        compose.onNodeWithText("Connect a repository").performClick()
        compose.onNodeWithText("Sync with a git repository").performClick()
        compose.onNode(hasText("Repository URL (HTTPS)") and hasSetTextAction()).performScrollTo().performTextInput(remote.toURI().toString())
        compose.onNodeWithText("Save and sync").performScrollTo().performClick()
        waitForText("Pancakes")

        // The remote refuses writes, like GitHub without a write token.
        fun setWritable(w: Boolean) = remote.walkTopDown().forEach { it.setWritable(w) }
        setWritable(false)
        try {
            compose.onNodeWithContentDescription("Add a recipe").performClick()
            compose.onNodeWithText("Write a recipe").performClick()
            compose.onNode(hasText("Title") and hasSetTextAction()).performScrollTo().performTextInput("Soupe")
            compose.onNode(hasText("Ingredients") and hasSetTextAction()).performScrollTo().performTextInput("2 poireaux")
            compose.onNode(hasText("Steps") and hasSetTextAction()).performScrollTo().performTextInput("Couper les poireaux.")
            compose.onNodeWithContentDescription("Save").performClick()
            waitForText(item("poireaux", "2"), substring = true)
            compose.onNodeWithContentDescription("Back").performClick()
            waitForText("Not synced", substring = true)
        } finally {
            setWritable(true)
        }

        compose.onNodeWithText("Retry").performClick()
        compose.waitUntilDoesNotExist(hasText("Not synced", substring = true), 20_000)
        Git.open(remote).use { git -> assertEquals("Add Soupe", git.log().call().first().shortMessage) }
    }

    @Test fun updateFromSettings() {
        TestHttpServer().use { server ->
            val apk = ByteArray(50_000) { it.toByte() }
            server.file("/app.apk", apk)
            server.json("/latest", TestHttpServer.releaseJson("9.9.9", server.base + "/app.apk", apk))
            Updater.latestReleaseUrl = server.base + "/latest"

            waitForText("Connect a repository")
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Installed version: ${BuildConfig.VERSION_NAME}").performScrollTo().assertExists()
            compose.onNodeWithText("Check for updates").performScrollTo().performClick()
            waitForText("Version 9.9.9 is available")
            compose.onNodeWithText("Download and install").performScrollTo().performClick()

            // First time: Android must let this app install apps.
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            waitForText("Install 9.9.9")
            val permission = shadowOf(app).nextStartedActivity
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.action)
            assertEquals("package:app.gramcookbook", permission.dataString)
            assertArrayEquals(apk, File(app.cacheDir, "updates/GramCookbook-9.9.9.apk").readBytes())

            // Allowed: Install opens the system installer on the downloaded file.
            shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
            compose.onNodeWithText("Install 9.9.9").performScrollTo().performClick()
            val install = shadowOf(app).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_VIEW, install.action)
            assertEquals("application/vnd.android.package-archive", install.type)
            assertEquals("content://app.gramcookbook.files/updates/GramCookbook-9.9.9.apk", install.dataString)
            assertTrue(install.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

            // The list shows the update too.
            compose.onNodeWithContentDescription("Back").performClick()
            waitForText("Version 9.9.9 is available")
        }
    }
}
