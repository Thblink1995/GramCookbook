package app.gramcookbook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.gramcookbook.data.GitConfig
import app.gramcookbook.data.Settings
import app.gramcookbook.data.Updater
import app.gramcookbook.ui.MainActivity
import org.eclipse.jgit.api.Git
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the book (contents, a chapter, a page being turned, a recipe) to build/screenshots (run with -Dscreenshots=true). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class BookScreenshotTest {
    @get:Rule(order = 0) val tmp = TemporaryFolder()

    // A repository with a few recipes, already connected when the app starts.
    @get:Rule(order = 1) val connected = object : ExternalResource() {
        override fun before() {
            assumeTrue(System.getProperty("screenshots") == "true")
            val app = ApplicationProvider.getApplicationContext<Context>()
            app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
            File(app.filesDir, "repos").deleteRecursively()
            Updater.latestReleaseUrl = "http://127.0.0.1:9/none"
            Settings(app).git = GitConfig(url = remote().toURI().toString(), authorName = "Test")
        }
    }

    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    private fun remote(): File {
        val seed = tmp.newFolder("seed")
        val pancakes = javaClass.classLoader!!.getResource("pancakes.gram")!!.readText()
        val recipes = mapOf(
            "pancakes.gram" to pancakes,
            "pain.gram" to "---\ntitle: 'Pain de campagne'\ncategory: 'Boulangerie'\nportions: 2\n---\n\n[Pétrir] la @farine{500g}, l'@eau{350g} et le @=sel{10g} ~{10min}.\n\n[Cuire] dans le #four à ^{230°C} ~{35min}.\n",
            "brioche.gram" to "---\ntitle: 'Brioche'\ncategory: 'Boulangerie'\n---\n\n[Pétrir] la @farine{500g} et le @beurre{250g}.\n",
            "soupe.gram" to "---\ntitle: 'Soupe de poireaux'\ncategory: 'Soupes'\nportions: 4\n---\n\n[Couper] les @poireaux{3}.\n",
            "tarte.gram" to "---\ntitle: 'Tarte aux pommes'\ncategory: 'Dessert'\n---\n\n[Disposer] les @pommes{4} sur la @pâte{1}.\n",
            "vinaigrette.gram" to "---\ntitle: 'Vinaigrette'\n---\n\n[Mélanger] l'@huile{3 cs} et le @vinaigre{1 cs}.\n",
        )
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            for ((name, text) in recipes) File(seed, name).writeText(text)
            git.add().addFilepattern(".").call()
            git.commit().setMessage("first").setAuthor("t", "t@t").setCommitter("t", "t@t").call()
        }
        val bare = File(tmp.root, "remote.git")
        Git.cloneRepository().setURI(seed.toURI().toString()).setDirectory(bare).setBare(true).call().close()
        return bare
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tour(theme: String) {
        compose.waitUntilAtLeastOneExists(hasText("Brioche"), 20_000)
        save("book-contents-$theme")

        compose.onNodeWithContentDescription("Boulangerie").performClick()
        compose.waitUntilAtLeastOneExists(hasText("CHAPTER I"), 5_000)
        save("book-chapter-$theme")

        // Halfway through turning to a recipe.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Pain de campagne").performClick()
        compose.mainClock.advanceTimeBy(300)
        save("book-turning-$theme")
        compose.mainClock.autoAdvance = true

        compose.waitUntilAtLeastOneExists(hasText("2 servings"), 5_000)
        save("book-recipe-$theme")
    }

    @Test @Config(qualifiers = "+notnight") fun light() = tour("light")

    @Test @Config(qualifiers = "+night") fun dark() = tour("dark")
}
