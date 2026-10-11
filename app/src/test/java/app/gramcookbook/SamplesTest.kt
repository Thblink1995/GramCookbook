package app.gramcookbook

import app.gramcookbook.data.WebImporter
import app.gramcookbook.gram.GramWriter
import app.gramcookbook.gram.RecipeDraft
import org.junit.Test
import java.io.File

/** Writes converter output to build/gram-samples so the official `gram check` can validate it. */
class SamplesTest {
    @Test fun writeSamples() {
        val out = File("build/gram-samples").apply { mkdirs() }
        File(out, "crepes.gram").writeText(
            GramWriter.toGram(
                RecipeDraft(
                    title = "Crêpes", portions = "4",
                    ingredients = "250 g de farine\n4 œufs\n50 cl de lait\n1 pincée de sel\n50 g de sucre en poudre\n1 sachet de sucre vanillé\n2 c. à soupe de rhum (facultatif)",
                    steps = "## Pâte\nMélanger la farine, le sel et les œufs dans un saladier.\nAjouter le lait & le sucre petit à petit. Ajouter le rhum.\nLaisser reposer 1 h.\n## Cuisson\nCuire 2 min par face (à feu vif) #1 ~ 20 crêpes | bon appétit // https://x.y",
                ),
            ),
        )
        File(out, "soup.gram").writeText(
            GramWriter.toGram(
                RecipeDraft(
                    title = "Leek soup", portions = "",
                    ingredients = "3 leeks, sliced\n2 potatoes\n1 1/2 cups of chicken stock\n1 tbsp butter\nsalt & pepper\n½ cup cream",
                    steps = "Melt the butter in a pot.\nAdd the leeks and potatoes; cook 10 min.\nPour the stock, simmer 20 min, blend with the cream.",
                ),
            ),
        )
        val html = """<script type="application/ld+json">{"@type":"Recipe","name":"Tarte","recipeYield":"6 parts",
            "recipeIngredient":["1 pâte brisée","4 pommes","1 c. à café de cannelle","30 g de beurre"],
            "recipeInstructions":[{"@type":"HowToStep","text":"Étaler la pâte dans le moule."},{"@type":"HowToStep","text":"Disposer les pommes, la cannelle et le beurre. Cuire 35 min à 180°C."}]}</script>"""
        File(out, "tarte.gram").writeText(GramWriter.toGram(WebImporter.fromHtml(html, "https://example.com/tarte")!!))
    }
}
