package app.gramcookbook

import app.gramcookbook.data.RecipeStore
import app.gramcookbook.data.Source
import app.gramcookbook.data.SourceSync
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SourceSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun followsTheRemoteAndNeverKeepsLocalChanges() {
        val author = File(tmp.root, "author")
        Git.init().setDirectory(author).setInitialBranch("main").call().use { git ->
            File(author, "pancakes.gram").writeText("---\ntitle: 'Pancakes'\n---\n\nMix @flour{100g}.\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("first").setAuthor("t", "t@t").setCommitter("t", "t@t").call()
        }
        val source = Source(author.toURI().toString())
        val copy = File(tmp.root, "copy")
        val sync = SourceSync(copy, source)

        assertTrue(sync.update())
        assertEquals(listOf("Pancakes"), RecipeStore(copy).list().map { it.title })
        assertFalse("nothing new", sync.update())

        // The author adds a recipe and changes one; a stray local change is discarded.
        Git.open(author).use { git ->
            File(author, "soup.gram").writeText("---\ntitle: 'Soup'\n---\n\nCut @leeks{2}.\n")
            File(author, "pancakes.gram").writeText("---\ntitle: 'Pancakes'\n---\n\nMix @flour{200g}.\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("second").setAuthor("t", "t@t").setCommitter("t", "t@t").call()
        }
        File(copy, "pancakes.gram").writeText("changed on the phone")
        assertTrue(sync.update())
        assertEquals(listOf("Pancakes", "Soup"), RecipeStore(copy).list().map { it.title })
        assertTrue(File(copy, "pancakes.gram").readText().contains("@flour{200g}"))
    }

    @Test fun nameIsOwnerAndRepository() {
        assertEquals("alice/recipes", Source("https://github.com/alice/recipes.git").name)
        assertEquals("alice/recipes", Source("https://github.com/alice/recipes/").name)
    }
}
