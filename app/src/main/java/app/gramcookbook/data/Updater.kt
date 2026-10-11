package app.gramcookbook.data

import app.gramcookbook.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The latest published release, as far as updating is concerned. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Hex SHA-256 of the APK, when the host publishes it (GitHub does, as `digest`). */
    val sha256: String?,
    val pageUrl: String,
)

class UpdateException(message: String) : Exception(message)

/** Finds and downloads new versions of the app from the GitHub releases of its repository. */
object Updater {
    /** Overridden in tests to point at a local server. */
    var latestReleaseUrl = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    fun latest(): Release? {
        val conn = URL(latestReleaseUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "GramCookbook/${BuildConfig.VERSION_NAME}")
        try {
            // No release published yet.
            if (conn.responseCode == 404) return null
            if (conn.responseCode !in 200..299) throw UpdateException("http-${conn.responseCode}")
            return parseRelease(conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }

    /** The release with its first `.apk` asset, or null when it has none. */
    fun parseRelease(json: String): Release? {
        val r = JSONObject(json)
        if (r.optBoolean("draft") || r.optBoolean("prerelease")) return null
        val assets = r.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
        return Release(
            version = r.optString("tag_name").removePrefix("v").removePrefix("V"),
            notes = r.optString("body"),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size", -1),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            pageUrl = r.optString("html_url"),
        )
    }

    /** Compares dotted versions numerically: 0.10.0 > 0.9.1, 1.0 == 1.0.0. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads the APK to [dest], checking its size and SHA-256 when known. */
    fun download(release: Release, dest: File, onProgress: (Float) -> Unit): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        // GitHub redirects downloads to its file host (HTTPS to HTTPS, followed automatically).
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "GramCookbook/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode !in 200..299) throw UpdateException("http-${conn.responseCode}")
            val total = release.apkSize.takeIf { it > 0 } ?: conn.contentLengthLong
            var read = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (release.apkSize > 0 && read != release.apkSize) throw UpdateException("size")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && hex != release.sha256) throw UpdateException("checksum")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        dest.delete()
        if (!part.renameTo(dest)) throw UpdateException("move")
        return dest
    }
}
