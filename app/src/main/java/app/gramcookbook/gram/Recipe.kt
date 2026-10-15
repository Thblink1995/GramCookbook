package app.gramcookbook.gram

data class Recipe(val meta: Map<String, String>, val sections: List<Section>) {
    val title: String? get() = meta["title"]?.takeIf { it.isNotBlank() }

    /** Baseline servings: `portions: 4` or `portions: 4 servings`. */
    val portions: Int?
        get() = meta["portions"]?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }?.takeIf { it > 0 }

    val tags: List<String> get() = GramParser.tags(meta["tags"])

    /** Preparation, cooking and total time, formatted. */
    val times: Map<Durations.Kind, String> get() = Durations.of(meta)

    /** Ingredients aggregated for the list at the top of a recipe, in order of first use. */
    fun ingredientList(): List<ListItem> {
        val items = LinkedHashMap<String, ListItem>()
        for (part in sections.flatMap { s -> s.steps.flatMap { it.parts } }) {
            if (part !is Part.Ingredient || part.isHidden) continue
            if (part.isReference && (part.quantity == null || part.quantity.isEmpty)) continue
            val item = items.getOrPut(part.name.lowercase()) { ListItem(part.name, mutableListOf(), part.isOptional) }
            val q = part.quantity?.takeUnless { it.isEmpty } ?: continue
            item.add(Amount(q, part.isFixed))
        }
        return items.values.toList()
    }

    fun cookware(): List<String> = sections.asSequence()
        .flatMap { s -> s.steps.flatMap { it.parts } }
        .filterIsInstance<Part.Cookware>()
        .filter { !it.isReference }
        .map { it.name }
        .distinctBy { it.lowercase() }
        .toList()
}

data class Section(val title: String?, val steps: List<Step>)

data class Step(val action: String?, val parts: List<Part>) {
    val isCommentOnly: Boolean
        get() = action == null && parts.all { it is Part.Comment || (it is Part.Text && it.text.isBlank()) }
}

data class Amount(val quantity: Quantity, val fixed: Boolean) {
    fun scaled(factor: Double) = if (fixed) quantity else quantity.scaled(factor)
}

class ListItem(val name: String, val amounts: MutableList<Amount>, val optional: Boolean) {
    /** Sums with an existing amount of the same unit; keeps it separate otherwise. */
    fun add(a: Amount) {
        val q = a.quantity
        val i = amounts.indexOfFirst { e ->
            val o = e.quantity
            e.fixed == a.fixed && o.unit.equals(q.unit, ignoreCase = true) &&
                o.low != null && q.low != null && o.high == null && q.high == null &&
                o.relativePercent == null && q.relativePercent == null
        }
        if (i < 0) amounts += a
        else amounts[i] = Amount(amounts[i].quantity.copy(low = amounts[i].quantity.low!! + q.low!!), a.fixed)
    }

    fun format(factor: Double): String = amounts.joinToString(" + ") { it.scaled(factor).format() }
}

sealed interface Part {
    data class Text(val text: String) : Part

    data class Ingredient(
        val name: String,
        val display: String,
        val quantity: Quantity?,
        val modifiers: String,
        val preparation: String?,
    ) : Part {
        val isReference get() = '&' in modifiers
        val isFixed get() = '=' in modifiers
        val isHidden get() = '-' in modifiers
        val isOptional get() = '?' in modifiers
    }

    data class Cookware(val name: String, val display: String, val modifiers: String) : Part {
        val isReference get() = '&' in modifiers
    }

    data class Timer(val name: String?, val passive: Boolean, val value: String) : Part
    data class Temperature(val value: String) : Part

    /** `&dough`: use of an intermediate preparation. */
    data class Reference(val name: String, val quantity: Quantity?) : Part

    /** `->&dough`: declares that a step produces an intermediate preparation. */
    data class Output(val name: String) : Part

    data class Comment(val text: String) : Part
}
