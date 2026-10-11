package app.gramcookbook

import app.gramcookbook.data.GitConfig
import app.gramcookbook.data.GitSync
import app.gramcookbook.data.RecipeStore
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun remoteWithOneRecipe(): File {
        val seed = tmp.newFolder("seed")
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            File(seed, "breakfast").mkdirs()
            File(seed, "breakfast/pancakes.gram").writeText("---\ntitle: 'Pancakes'\n---\n\nMix @flour{100g}.\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("first").setAuthor("t", "t@t").setCommitter("t", "t@t").call()
        }
        val bare = File(tmp.root, "remote.git")
        Git.cloneRepository().setURI(seed.toURI().toString()).setDirectory(bare).setBare(true).call().close()
        return bare
    }

    @Test fun twoDevicesStayInSync() {
        val remote = remoteWithOneRecipe()
        val config = GitConfig(url = remote.toURI().toString(), authorName = "Phone", authorEmail = "phone@example.com")
        val phone = File(tmp.root, "phone")
        val laptop = File(tmp.root, "laptop")

        // A recipe written before the first clone must survive it.
        RecipeStore(phone).write("desserts/crepes.gram", "---\ntitle: 'Crêpes'\n---\n\nMix @flour{250g}.\n")
        assertTrue(GitSync(phone, config).sync("Add Crêpes"))
        assertEquals(listOf("Pancakes", "Crêpes").sorted(), RecipeStore(phone).list().map { it.title }.sorted())

        GitSync(laptop, config).cloneIfNeeded()
        assertTrue(File(laptop, "desserts/crepes.gram").exists())

        // Edits on both sides, in different files, merge cleanly.
        File(laptop, "breakfast/pancakes.gram").appendText("\nServe hot.\n")
        assertTrue(GitSync(laptop, config).sync("Edit pancakes"))
        RecipeStore(phone).delete("desserts/crepes.gram")
        assertTrue(GitSync(phone, config).sync("Delete crêpes"))
        assertTrue(File(phone, "breakfast/pancakes.gram").readText().contains("Serve hot."))

        assertTrue(GitSync(laptop, config).sync("noop"))
        assertFalse(File(laptop, "desserts/crepes.gram").exists())
        assertFalse(GitSync(laptop, config).sync("noop"))

        Git.open(phone).use { git ->
            assertEquals(0, git.repository.config.getInt("gc", "auto", -1))
            assertEquals(0, git.repository.config.getInt("gc", "autopacklimit", -1))
        }

        Git.open(remote).use { git ->
            val messages = git.log().call().map { it.shortMessage }
            assertEquals(listOf("Delete crêpes", "Edit pancakes", "Add Crêpes", "first"), messages)
            assertEquals("Phone", git.log().call().first().authorIdent.name)
        }
    }

    @Test fun conflictIsReportedAndLeavesLocalWorkIntact() {
        val remote = remoteWithOneRecipe()
        val config = GitConfig(url = remote.toURI().toString())
        val a = File(tmp.root, "a")
        val b = File(tmp.root, "b")
        GitSync(a, config).cloneIfNeeded()
        GitSync(b, config).cloneIfNeeded()
        File(a, "breakfast/pancakes.gram").writeText("---\ntitle: 'A'\n---\n")
        GitSync(a, config).sync("A")
        File(b, "breakfast/pancakes.gram").writeText("---\ntitle: 'B'\n---\n")
        val e = runCatching { GitSync(b, config).sync("B") }.exceptionOrNull()
        assertEquals("conflict", e?.message)
        assertEquals("---\ntitle: 'B'\n---\n", File(b, "breakfast/pancakes.gram").readText())
    }
}
