package app.gramcookbook

import app.gramcookbook.data.WebImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebImporterTest {
    @Test fun readsRecipeInGraphWithSections() {
        val html = """
            <html><head>
            <script type="application/ld+json">{"@context":"https://schema.org","@type":"WebSite","name":"x"}</script>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[{"@type":"Organization"},{"@type":["Recipe"],
              "name":"Tarte aux pommes &amp; cannelle","recipeCategory":["Dessert","Tarte"],"recipeYield":["6","6 parts"],
              "author":{"@type":"Person","name":"Anne"},
              "recipeIngredient":["1 p&acirc;te bris&eacute;e","4 pommes","1 c. &agrave; caf&eacute; de cannelle"],
              "recipeInstructions":[{"@type":"HowToSection","name":"Préparation","itemListElement":[
                 {"@type":"HowToStep","text":"Éplucher les <b>pommes</b>."},
                 {"@type":"HowToStep","text":"Étaler la pâte."}]},
                 {"@type":"HowToStep","text":"Cuire 35&nbsp;min à 180&#176;C."}]}]}
            </script></head></html>
        """.trimIndent()
        val d = WebImporter.fromHtml(html, "https://example.com/tarte")!!
        assertEquals("Tarte aux pommes & cannelle", d.title)
        assertEquals("6", d.portions)
        assertEquals("Anne", d.author)
        assertEquals("Dessert", d.category)
        assertEquals("1 pâte brisée\n4 pommes\n1 c. à café de cannelle", d.ingredients)
        assertEquals("## Préparation\nÉplucher les pommes.\nÉtaler la pâte.\nCuire 35 min à 180°C.", d.steps)
        assertEquals("https://example.com/tarte", d.source)
    }

    @Test fun readsPlainStringInstructions() {
        val html = """<script type='application/ld+json'>{"@type":"Recipe","name":"Soup","recipeIngredient":["2 leeks"],
            "recipeInstructions":"Cut the leeks.<br/>Boil 20 min."}</script>"""
        val d = WebImporter.fromHtml(html, "u")!!
        assertEquals("Cut the leeks.\nBoil 20 min.", d.steps)
    }

    @Test fun readsKeywordsAndTimes() {
        val html = """<script type="application/ld+json">{"@type":"Recipe","name":"Soup","recipeIngredient":["2 leeks"],
            "keywords":"soup, winter","prepTime":"PT15M","cookTime":"PT1H","totalTime":"PT1H15M"}</script>"""
        val d = WebImporter.fromHtml(html, "u")!!
        assertEquals("soup, winter", d.tags)
        assertEquals("15", d.prepTime)
        assertEquals("60", d.cookTime)
        assertEquals("75", d.totalTime)
    }

    @Test fun noRecipe() {
        assertNull(WebImporter.fromHtml("<html><body>hello</body></html>", "u"))
    }
}
