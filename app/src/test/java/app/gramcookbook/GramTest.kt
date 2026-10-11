package app.gramcookbook

import app.gramcookbook.gram.GramParser
import app.gramcookbook.gram.GramWriter
import app.gramcookbook.gram.ParsedIngredient
import app.gramcookbook.gram.Part
import app.gramcookbook.gram.Quantity
import app.gramcookbook.gram.RecipeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GramTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun quantities() {
        assertEquals(Quantity(250.0, null, "g"), Quantity.parse("250g"))
        assertEquals(Quantity(1.5, null, "cups"), Quantity.parse("1 1/2 cups"))
        assertEquals(Quantity(2.0, 4.0, ""), Quantity.parse("2-4"))
        assertEquals(Quantity(0.5, null, "c.à.s"), Quantity.parse("½ c.à.s"))
        assertEquals(Quantity(null, null, "pincée"), Quantity.parse("pincée"))
        val rel = Quantity.parse("70% @&flour")
        assertEquals(70.0, rel.relativePercent)
        assertEquals("flour", rel.relativeTarget)
    }

    @Test fun scalingAndFormatting() {
        assertEquals("375 g", Quantity.parse("250g").scaled(1.5).format())
        assertEquals("1½ tbsp", Quantity.parse("1 tbsp").scaled(1.5).format())
        assertEquals("3", Quantity.parse("2").scaled(1.5).format())
        assertEquals("0.8 l", Quantity.parse("0.5 l").scaled(1.5).format())
        assertEquals("3–6", Quantity.parse("2-4").scaled(1.5).format())
        assertEquals("⅔ cup", Quantity.parse("1 cup").scaled(2.0 / 3).format())
        assertEquals("70 % flour", Quantity.parse("70% @&flour").scaled(3.0).format())
    }

    @Test fun parsesElements() {
        val r = GramParser.parse(
            """
            ---
            title: 'Bread'
            portions: 4 servings
            ---

            ## Dough ->&dough

            [Mix] The @flour{500g}, @water{70% @&flour}, @=salt{10g} and @?thyme in a #large bowl{}. ->&dough

            [Rest] Let the &dough rest for ~_{2h} at ^{room temperature}. // covered

            [Bake] @dry white wine:wine{10cl}(cold) then ~oven{35min} at ^{220°C}.
            """.trimIndent(),
        )
        assertEquals("Bread", r.title)
        assertEquals(4, r.portions)
        assertEquals("Dough", r.sections.single().title)
        val steps = r.sections.single().steps
        assertEquals(listOf("Mix", "Rest", "Bake"), steps.map { it.action })
        val ingredients = steps[0].parts.filterIsInstance<Part.Ingredient>()
        assertEquals(listOf("flour", "water", "salt", "thyme"), ingredients.map { it.name })
        assertTrue(ingredients[2].isFixed)
        assertTrue(ingredients[3].isOptional)
        assertEquals("large bowl", steps[0].parts.filterIsInstance<Part.Cookware>().single().name)
        assertEquals(Part.Output("dough"), steps[0].parts.last())
        assertTrue(steps[1].parts.contains(Part.Timer(null, true, "2h")))
        assertTrue(steps[1].parts.contains(Part.Reference("dough", null)))
        assertTrue(steps[1].parts.contains(Part.Comment("covered")))
        val wine = steps[2].parts.filterIsInstance<Part.Ingredient>().single()
        assertEquals("dry white wine", wine.name)
        assertEquals("wine", wine.display)
        assertEquals("cold", wine.preparation)
        assertTrue(steps[2].parts.contains(Part.Timer("oven", false, "35min")))
    }

    @Test fun bareNamesStopAtWordsAndPunctuation() {
        val parts = GramParser.parseInline("Add @salt and #pan, then @&salt.")
        assertEquals(Part.Ingredient("salt", "salt", null, "", null), parts[1])
        assertEquals("pan", (parts[3] as Part.Cookware).name)
        assertEquals(Part.Ingredient("salt", "salt", null, "&", null), parts[5])
        assertEquals(Part.Text("."), parts.last())
    }

    @Test fun ingredientListAggregatesAndScales() {
        val r = GramParser.parse(resource("pancakes.gram"))
        assertEquals(2, r.portions)
        val list = r.ingredientList().associateBy { it.name }
        // Declared once and added again with @&œuf{1}: 2 in total.
        assertEquals("2", list.getValue("œuf").format(1.0))
        assertEquals("300 g", list.getValue("farine").format(1.0))
        assertEquals("600 g", list.getValue("farine").format(2.0))
        assertEquals("400 ml", list.getValue("lait").format(1.0))
        assertEquals(listOf("saladier", "poêle", "louche", "spatule"), r.cookware())
    }

    @Test fun parsesIngredientLines() {
        fun p(s: String) = GramWriter.parseIngredient(s)
        assertEquals("farine", p("250 g de farine")!!.name)
        assertEquals("250g", p("250 g de farine")!!.quantity)
        assertEquals("4", p("4 œufs")!!.quantity)
        assertEquals("œufs", p("4 œufs")!!.name)
        assertEquals("2 c.à.s", p("2 cuillères à soupe d'huile d'olive")!!.quantity)
        assertEquals("huile d'olive", p("2 cuillères à soupe d'huile d'olive")!!.name)
        assertEquals("1 pincée", p("1 pincée de sel")!!.quantity)
        assertEquals("1 pincée", p("une pincée de sel")!!.quantity)
        assertEquals("1.5l", p("1,5 l de lait")!!.quantity)
        assertEquals("2 gousses", p("2 gousses d'ail")!!.quantity)
        assertEquals("1/2 cup", p("1/2 cup of sugar")!!.quantity)
        assertEquals("sugar", p("1/2 cup of sugar")!!.name)
        val onion = p("1 oignon, émincé")!!
        assertEquals("oignon", onion.name)
        assertEquals("émincé", onion.preparation)
        assertEquals("", p("Sel et poivre")!!.quantity)
        assertEquals("salt and pepper", GramWriter.parseIngredient("Salt & pepper", french = false)!!.name)
        val rum = p("2 c. à soupe de rhum (facultatif)")!!
        assertTrue(rum.optional)
        assertNull(rum.preparation)
        assertNull(p("Pour la pâte :"))
        assertEquals("2-3", p("2 à 3 carottes")!!.quantity)
        assertEquals(ParsedIngredient("levure", "1 bonne c.à.c", null), p("1 bonne cuillère à café de levure"))
        assertEquals(ParsedIngredient("huile", "1 c.à.s", "ou beurre fondu"), p("1 cuillère à soupe d'huile ou de beurre fondu"))
    }

    @Test fun setsFrontmatterKeys() {
        val src = "---\ntitle: 'Pancakes'\ncategory: 'Dessert'\nportions: 2\n---\n\n[Mix] @flour{100g}.\n"
        val changed = GramWriter.setMeta(src, "category", "Petit-déjeuner d'hiver")
        assertEquals("---\ntitle: 'Pancakes'\ncategory: 'Petit-déjeuner d''hiver'\nportions: 2\n---\n\n[Mix] @flour{100g}.\n", changed)
        assertEquals("Petit-déjeuner d'hiver", GramParser.readMeta(changed)["category"])
        assertEquals("---\ntitle: 'Pancakes'\nportions: 2\n---\n\n[Mix] @flour{100g}.\n", GramWriter.setMeta(src, "category", " "))
        assertEquals("---\ntitle: 'Soup'\ncategory: 'Plat'\n---\n", GramWriter.setMeta("---\ntitle: 'Soup'\n---\n", "category", "Plat"))
        assertEquals("---\ncategory: 'Plat'\n---\n\nMix.\n", GramWriter.setMeta("Mix.\n", "category", "Plat"))
    }

    @Test fun writesGramThatReadsBack() {
        val gram = GramWriter.toGram(
            RecipeDraft(
                title = "Crêpes d'Anne",
                portions = "4",
                ingredients = "250 g de farine\n4 œufs\n50 cl de lait\n1 pincée de sel\n50 g de sucre en poudre\nvanille",
                steps = "## Pâte\nMélanger la farine, le sel et les œufs dans un saladier.\nAjouter le lait & le sucre petit à petit.\n## Cuisson\nCuire 2 min par face #1 (à feu vif).",
                source = "https://example.com/crepes",
                category = "Dessert",
            ),
        )
        assertTrue(gram, gram.contains("category: 'Dessert'"))
        assertTrue(gram, gram.contains("title: 'Crêpes d''Anne'"))
        assertTrue(gram, gram.contains("la @farine{250g}"))
        assertTrue(gram, gram.contains("le @sel{1 pincée}"))
        assertTrue(gram, gram.contains("les @œufs{4}"))
        assertTrue(gram, gram.contains("le @sucre en poudre:sucre{50g}"))
        assertTrue(gram, gram.contains("Ingrédients : @vanille{}."))
        assertTrue(gram, gram.contains("lait{50cl} et le"))
        assertTrue(gram, gram.contains("n°1"))

        val r = GramParser.parse(gram)
        assertEquals("Crêpes d'Anne", r.title)
        assertEquals(4, r.portions)
        assertEquals(listOf(null, "Pâte", "Cuisson"), r.sections.map { it.title })
        assertEquals(
            listOf("vanille", "farine", "sel", "œufs", "lait", "sucre en poudre"),
            r.ingredientList().map { it.name },
        )
    }
}

class GramWriterEnglishTest {
    @Test fun matchesLastWordInEnglish() {
        val gram = GramWriter.toGram(RecipeDraft(title = "Soup", ingredients = "1 1/2 cups of chicken stock\n1 tbsp butter", steps = "Melt the butter in a pot.\nPour the stock and simmer."))
        assertTrue(gram, gram.contains("the @chicken stock:stock{1 1/2 cup} and"))
        assertTrue(gram, !gram.contains("Ingredients:"))
    }
}
