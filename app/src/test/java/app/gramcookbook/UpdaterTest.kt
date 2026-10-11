package app.gramcookbook

import app.gramcookbook.data.Release
import app.gramcookbook.data.Updater
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdaterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = TestHttpServer()
    private val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }

    @After fun stop() {
        server.close()
        Updater.latestReleaseUrl = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
    }

    @Test fun comparesVersions() {
        assertTrue(Updater.isNewer("0.2.0", "0.1.0"))
        assertTrue(Updater.isNewer("v0.10.0", "0.9.1"))
        assertTrue(Updater.isNewer("1.0.1", "1.0"))
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertFalse(Updater.isNewer("0.1.0", "0.2.0"))
        assertFalse(Updater.isNewer("0.2.0", "0.2.0"))
    }

    @Test fun readsGithubRelease() {
        // Shape of the real answer for v0.1.0 (api.github.com/repos/Thblink1995/GramCookbook/releases/latest).
        val r = Updater.parseRelease(
            """{"tag_name":"v0.1.0","html_url":"https://github.com/Thblink1995/GramCookbook/releases/tag/v0.1.0","draft":false,"prerelease":false,
               "body":"First release","assets":[{"name":"GramCookbook-0.1.0.apk","size":7588706,
               "content_type":"application/vnd.android.package-archive",
               "digest":"sha256:97062e31722ebc162a3b06a82ad19e45edda4008d405088e085cf0e89de6a8c3",
               "browser_download_url":"https://github.com/Thblink1995/GramCookbook/releases/download/v0.1.0/GramCookbook-0.1.0.apk"}]}""",
        )!!
        assertEquals("0.1.0", r.version)
        assertEquals(7588706L, r.apkSize)
        assertEquals("97062e31722ebc162a3b06a82ad19e45edda4008d405088e085cf0e89de6a8c3", r.sha256)
        assertEquals("https://github.com/Thblink1995/GramCookbook/releases/download/v0.1.0/GramCookbook-0.1.0.apk", r.apkUrl)
        assertNull(Updater.parseRelease("""{"tag_name":"v1","assets":[{"name":"notes.txt","browser_download_url":"x"}]}"""))
    }

    @Test fun findsLatestAndDownloadsVerified() {
        server.file("/app.apk", apk)
        server.json("/latest", TestHttpServer.releaseJson("0.3.0", server.base + "/app.apk", apk))
        Updater.latestReleaseUrl = server.base + "/latest"

        val release = Updater.latest()!!
        assertEquals("0.3.0", release.version)
        val progress = mutableListOf<Float>()
        val file = Updater.download(release, File(tmp.root, "updates/app.apk")) { progress += it }
        assertArrayEquals(apk, file.readBytes())
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun noReleaseYet() {
        Updater.latestReleaseUrl = server.base + "/missing"
        assertNull(Updater.latest())
    }

    @Test fun rejectsAlteredOrIncompleteDownloads() {
        server.file("/app.apk", apk)
        val dest = File(tmp.root, "app.apk")
        fun release(size: Long, sha: String?) = Release("0.3.0", "", server.base + "/app.apk", size, sha, "")

        val altered = runCatching { Updater.download(release(apk.size.toLong(), "0".repeat(64), ), dest) {} }.exceptionOrNull()
        assertEquals("checksum", altered?.message)
        val truncated = runCatching { Updater.download(release(apk.size + 10L, null), dest) {} }.exceptionOrNull()
        assertEquals("size", truncated?.message)
        // Nothing half-downloaded is left behind.
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }
}
