package app.gramcookbook

import java.net.InetAddress
import java.net.ServerSocket

/** A tiny HTTP/1.1 server for tests: each path answers with a fixed status, type and body. */
class TestHttpServer : AutoCloseable {
    class Response(val status: String, val type: String, val body: ByteArray)

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val routes = mutableMapOf<String, Response>()
    val base get() = "http://127.0.0.1:${socket.localPort}"

    init {
        Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val r = routes[path] ?: Response("404 Not Found", "text/plain", ByteArray(0))
                    val out = it.getOutputStream()
                    out.write("HTTP/1.1 ${r.status}\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(r.body)
                    out.flush()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun json(path: String, json: String) {
        routes[path] = Response("200 OK", "application/json", json.toByteArray())
    }

    fun file(path: String, bytes: ByteArray) {
        routes[path] = Response("200 OK", "application/vnd.android.package-archive", bytes)
    }

    override fun close() = socket.close()

    companion object {
        fun sha256(bytes: ByteArray) =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** A GitHub "latest release" answer with one APK asset. */
        fun releaseJson(version: String, apkUrl: String, apk: ByteArray, digest: String = "sha256:" + sha256(apk)) = """
            {"tag_name":"v$version","name":"Gram Cookbook $version","draft":false,"prerelease":false,
             "html_url":"https://github.com/Thblink1995/GramCookbook/releases/tag/v$version",
             "body":"**New** in $version: things.\n\nSHA-256: `x`",
             "assets":[{"name":"GramCookbook-$version.apk","size":${apk.size},"digest":"$digest",
                        "browser_download_url":"$apkUrl"}]}
        """.trimIndent()
    }
}
