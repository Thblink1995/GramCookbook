package app.gramcookbook

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.gramcookbook.ui.AppTheme
import app.gramcookbook.ui.EditorPreview
import app.gramcookbook.ui.RecipePreview
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders screens to build/screenshots for a visual check (run with -Dscreenshots=true). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val source = javaClass.classLoader!!.getResource("pancakes.gram")!!.readText()

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "+notnight") fun recipeLight() {
        assumeTrue(System.getProperty("screenshots") == "true")
        compose.setContent { AppTheme { RecipePreview(source) } }
        save("recipe-light")
    }

    @Test @Config(qualifiers = "+night") fun recipeDark() {
        assumeTrue(System.getProperty("screenshots") == "true")
        compose.setContent { AppTheme { RecipePreview(source) } }
        save("recipe-dark")
    }

    @Test @Config(qualifiers = "+notnight") fun editorLight() {
        assumeTrue(System.getProperty("screenshots") == "true")
        compose.setContent { AppTheme { EditorPreview(source) } }
        save("editor-light")
    }

    @Test @Config(qualifiers = "+night") fun editorDark() {
        assumeTrue(System.getProperty("screenshots") == "true")
        compose.setContent { AppTheme { EditorPreview(source) } }
        save("editor-dark")
    }

    @Test @Config(qualifiers = "+notnight") fun allElements() {
        assumeTrue(System.getProperty("screenshots") == "true")
        val src = """
            ---
            title: 'Pain'
            portions: 2
            ---

            ## Pâte ->&pâte

            [Pétrir] la @*farine{500g}, l'@eau{350g}(tiède), le @=sel{10g} et le @?romarin dans un #saladier{} pendant ~{10min}. ->&pâte

            [Lever] Laisser lever la &pâte ~_{2h}. // sous un torchon

            ## Cuisson

            [Cuire] La &pâte{1/2} dans le #four à ^{230°C} pendant ~{35min}.
        """.trimIndent()
        compose.setContent { AppTheme { RecipePreview(src) } }
        save("elements-light")
    }
}
