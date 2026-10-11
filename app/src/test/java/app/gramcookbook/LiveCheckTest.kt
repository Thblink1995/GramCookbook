package app.gramcookbook

import app.gramcookbook.data.GitConfig
import app.gramcookbook.data.GitSync
import app.gramcookbook.data.RecipeStore
import app.gramcookbook.data.WebImporter
import app.gramcookbook.gram.GramWriter
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Network checks, run only with -Dlive=true. */
class LiveCheckTest {
    @Test fun importRealPage() {
        assumeTrue(System.getProperty("live") == "true")
        val d = WebImporter.fetch("https://www.marmiton.org/recettes/recette_pancakes-faciles-et-rapides_81518.aspx")
        println("DRAFT title=${d.title} portions=${d.portions}\n--ingredients--\n${d.ingredients}\n--steps--\n${d.steps}")
        File("build/gram-samples").mkdirs()
        File("build/gram-samples/live-pancakes.gram").writeText(GramWriter.toGram(d))
    }

    @Test fun cloneRealRepo() {
        assumeTrue(System.getProperty("live") == "true")
        val dir = File("build/live-clone").apply { deleteRecursively() }
        GitSync(dir, GitConfig("https://github.com/Thblink1995/recipes.git")).cloneIfNeeded()
        println("CLONED " + RecipeStore(dir).list())
    }

    @Test fun checkRealRepoAccess() {
        assumeTrue(System.getProperty("live") == "true")
        val url = "https://github.com/Thblink1995/recipes.git"
        println("ACCESS no token: " + GitSync(File("build/ac1"), GitConfig(url)).checkAccess())
        println("ACCESS bad token: " + GitSync(File("build/ac2"), GitConfig(url, token = "github_pat_invalid")).checkAccess())
    }
}
