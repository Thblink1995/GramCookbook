package app.gramcookbook.data

import app.gramcookbook.gram.GramParser
import java.io.File

/**
 * A recipe file, by path relative to the repository root (`breakfast/pancakes.gram`).
 * Recipes of a read-only [Source] have its id as a prefix (`<id>:breakfast/pancakes.gram`) and its [source] name.
 */
data class RecipeEntry(
    val path: String,
    val title: String,
    val category: String,
    val source: String? = null,
    val tags: List<String> = emptyList(),
    val ingredients: List<String> = emptyList(),
) {
    /** What the contents' search looks in, folded like [RecipeStore.fold]. */
    val searchText: String by lazy { RecipeStore.fold(listOf(title, path, tags.joinToString(" "), ingredients.joinToString(" ")).joinToString(" ")) }
}

/** The working copy of the git repository is the whole database: one `.gram` file per recipe. */
class RecipeStore(val root: File) {
    fun list(): List<RecipeEntry> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith('.') }
            .filter { it.isFile && it.extension == "gram" }
            .map { f ->
                val path = f.relativeTo(root).invariantSeparatorsPath
                // The whole recipe, for its ingredients: the search looks in them too.
                val recipe = runCatching { GramParser.parse(f.readText()) }.getOrNull()
                val meta = recipe?.meta.orEmpty()
                RecipeEntry(
                    path = path,
                    title = meta["title"]?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension,
                    category = meta["category"]?.trim().orEmpty(),
                    tags = recipe?.tags.orEmpty(),
                    ingredients = recipe?.ingredientList()?.map { it.name }.orEmpty(),
                )
            }
            .sortedBy { it.title.lowercase() }
            .toList()
    }

    fun read(path: String): String = file(path).readText()

    fun write(path: String, text: String) {
        val f = file(path)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun delete(path: String) {
        file(path).delete()
    }

    /** A free path for a new recipe at the root of the repository, e.g. `crepes.gram`. */
    fun newPath(title: String): String {
        val base = slug(title).ifEmpty { "recipe" }
        var candidate = "$base.gram"
        var n = 2
        while (file(candidate).exists()) candidate = "$base-${n++}.gram"
        return candidate
    }

    /** Copies every recipe into [target] (same paths, replacing what is there); with [move], removes them here. */
    fun copyTo(target: RecipeStore, move: Boolean = false): Int {
        val entries = list()
        for (e in entries) {
            target.write(e.path, read(e.path))
            if (move) delete(e.path)
        }
        return entries.size
    }

    private fun file(path: String): File {
        val f = File(root, path).canonicalFile
        require(f.path.startsWith(root.canonicalPath + File.separator)) { "Path outside repository: $path" }
        return f
    }

    companion object {
        fun slug(s: String): String = fold(s).take(60)

        /** Lowercase, without accents, words joined by `-`: `Œufs brouillés` -> `oeufs-brouilles`. */
        fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}"""), "")
            .replace("œ", "oe").replace("æ", "ae")
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
    }
}
