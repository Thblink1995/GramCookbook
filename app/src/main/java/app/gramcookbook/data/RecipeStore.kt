package app.gramcookbook.data

import app.gramcookbook.gram.GramParser
import java.io.File

/**
 * A recipe file, by path relative to the repository root (`breakfast/pancakes.gram`).
 * Recipes of a read-only [Source] have its id as a prefix (`<id>:breakfast/pancakes.gram`) and its [source] name.
 */
data class RecipeEntry(val path: String, val title: String, val category: String, val source: String? = null)

/** The working copy of the git repository is the whole database: one `.gram` file per recipe. */
class RecipeStore(val root: File) {
    fun list(): List<RecipeEntry> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith('.') }
            .filter { it.isFile && it.extension == "gram" }
            .map { f ->
                val path = f.relativeTo(root).invariantSeparatorsPath
                val meta = runCatching { GramParser.readMeta(f.readText()) }.getOrDefault(emptyMap())
                RecipeEntry(
                    path = path,
                    title = meta["title"]?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension,
                    category = meta["category"]?.trim().orEmpty(),
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
        fun slug(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}"""), "")
            .replace("œ", "oe").replace("æ", "ae")
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
            .take(60)
    }
}
