package com.minifile.explorer.core.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppUpdaterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: LoopbackHttpServer
    private var port: Int = 0

    @Before
    fun setUp() {
        server = LoopbackHttpServer()
        server.start()
        port = server.port
    }

    @After
    fun tearDown() {
        server.close()
    }

    private data class HttpResponse(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    private class LoopbackHttpServer : AutoCloseable {
        private val routes = ConcurrentHashMap<String, () -> HttpResponse>()
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        @Volatile
        private var closed = false

        val port: Int get() = serverSocket.localPort

        fun route(path: String, handler: () -> HttpResponse) {
            routes[path] = handler
        }

        fun start() {
            executor.submit {
                while (!closed) {
                    val sock = try {
                        serverSocket.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    executor.submit { handleClient(sock) }
                }
            }
        }

        private fun handleClient(sock: Socket) {
            sock.use { s ->
                s.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val reqLine = reader.readLine() ?: return
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val rawTarget = reqLine.split(' ').getOrNull(1) ?: "/"
                val pathOnly = rawTarget.substringBefore('?')
                val resp = routes[rawTarget]?.invoke()
                    ?: routes[pathOnly]?.invoke()
                    ?: HttpResponse(404)

                val out = s.getOutputStream()
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ${resp.status} ${reasonPhrase(resp.status)}\r\n")
                sb.append("Connection: close\r\n")
                if ("Content-Length" !in resp.headers) {
                    sb.append("Content-Length: ${resp.body.size}\r\n")
                }
                for ((k, v) in resp.headers) {
                    sb.append("$k: $v\r\n")
                }
                sb.append("\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (resp.body.isNotEmpty()) {
                    out.write(resp.body)
                }
                out.flush()
            }
        }

        private fun reasonPhrase(code: Int): String = when (code) {
            200 -> "OK"
            301, 302, 303, 307, 308 -> "Redirect"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            else -> "Status"
        }

        override fun close() {
            closed = true
            runCatching { serverSocket.close() }
            executor.shutdownNow()
        }
    }

    private fun buildValidApkBytes(extraSize: Int = 4096): ByteArray {
        val baos = ByteArrayOutputStream()
        val payload = ByteArray(extraSize)
        java.util.Random(42L).nextBytes(payload)
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(payload)
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    @Test
    fun loopbackHttpServerHappyPathRedirectsRateLimitAndSchemeEnforcement() {
        val apkBytes = buildValidApkBytes(512 * 1024)
        // /releases/latest returns 404 -> falls back to /releases
        server.route("/releases/latest") { HttpResponse(404) }
        server.route("/releases") {
            val body = """
                [
                  {
                    "tag_name": "v1.1.0",
                    "name": "MiniFile v1.1.0",
                    "body": "Bug fixes",
                    "prerelease": false,
                    "draft": false,
                    "assets": [{
                      "name": "minifile-v1.1.0-release.apk",
                      "browser_download_url": "http://127.0.0.1:$port/r/1",
                      "size": ${apkBytes.size}
                    }]
                  }
                ]
            """.trimIndent().toByteArray()
            HttpResponse(200, mapOf("Content-Type" to "application/json"), body)
        }

        // 5-hop redirect chain /r/1 -> /r/2 -> /r/3 -> /r/4 -> /r/5 -> /apk.bin (succeeds)
        for (hop in 1..5) {
            val nextTarget = if (hop < 5) "http://127.0.0.1:$port/r/${hop + 1}" else "http://127.0.0.1:$port/apk.bin"
            server.route("/r/$hop") {
                HttpResponse(302, mapOf("Location" to nextTarget))
            }
        }
        server.route("/apk.bin") {
            HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/vnd.android.package-archive"),
                body = apkBytes,
            )
        }

        // Default allowInsecureLoopback = false MUST reject plain http://127.0.0.1
        val strictUpdater = AppUpdater(
            latestUrl = "http://127.0.0.1:$port/releases/latest",
            listUrl = "http://127.0.0.1:$port/releases",
        )
        try {
            strictUpdater.checkLatestRelease("1.0.0")
            fail("Expected INSECURE_URL when allowInsecureLoopback is false")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.INSECURE_URL, e.code)
        }

        // With allowInsecureLoopback = true, loopback server works
        val loopbackUpdater = AppUpdater(
            latestUrl = "http://127.0.0.1:$port/releases/latest",
            listUrl = "http://127.0.0.1:$port/releases",
            allowInsecureLoopback = true,
        )
        val check = loopbackUpdater.checkLatestRelease("1.0.0-DEBUG")
        assertTrue(check.updateAvailable)
        assertEquals("v1.1.0", check.latest.tagName)

        val dest = File(tempFolder.newFolder("updates"), "minifile-1.1.0.apk")
        val progresses = mutableListOf<Long>()
        val out = loopbackUpdater.downloadApk(
            url = check.apk!!.url,
            dest = dest,
            expectedSize = check.apk!!.size,
            onProgress = { done, _ -> progresses.add(done) },
        )
        assertEquals(dest, out)
        assertTrue(dest.isFile)
        assertFalse(File(dest.path + ".tmp").exists())
        assertTrue("Expected multiple progress callbacks", progresses.size >= 2)
        assertEquals(apkBytes.size.toLong(), progresses.last())
        for (i in 1 until progresses.size) {
            assertTrue("Progress must be monotonic", progresses[i] >= progresses[i - 1])
        }

        // 6-hop redirect fails
        server.route("/too-many") {
            HttpResponse(302, mapOf("Location" to "http://127.0.0.1:$port/r/1"))
        }
        try {
            loopbackUpdater.downloadApk("http://127.0.0.1:$port/too-many", File(dest.parentFile, "fail6.apk"), apkBytes.size.toLong())
            fail("Expected >5 redirects failure")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.NETWORK, e.code)
        }

        // Even with allowInsecureLoopback = true, redirect to non-loopback http:// MUST fail with INSECURE_URL
        server.route("/foreign-http") {
            HttpResponse(302, mapOf("Location" to "http://evil.example.com/minifile.apk"))
        }
        try {
            loopbackUpdater.downloadApk("http://127.0.0.1:$port/foreign-http", File(dest.parentFile, "foreign.apk"), apkBytes.size.toLong())
            fail("Expected INSECURE_URL on non-loopback http redirect")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.INSECURE_URL, e.code)
        }

        // https -> http redirect downgrade MUST fail even if redirect points to 127.0.0.1!
        server.route("/downgrade-loopback") {
            HttpResponse(302, mapOf("Location" to "http://127.0.0.1:$port/apk.bin"))
        }
        val seamUpdater = AppUpdater(
            allowInsecureLoopback = true,
            openConnection = { url ->
                @Suppress("DEPRECATION")
                URL("http", "127.0.0.1", port, url.file).openConnection() as HttpURLConnection
            },
        )
        try {
            seamUpdater.downloadApk("https://cdn.github.test/downgrade-loopback", File(dest.parentFile, "down.apk"), apkBytes.size.toLong())
            fail("Expected INSECURE_URL on https->http downgrade")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.INSECURE_URL, e.code)
        }

        // Rate limit 403
        server.route("/ratelimited") {
            HttpResponse(403, mapOf("X-RateLimit-Remaining" to "0"))
        }
        try {
            AppUpdater(
                latestUrl = "http://127.0.0.1:$port/ratelimited",
                listUrl = "",
                allowInsecureLoopback = true,
            ).checkLatestRelease("1.0.0")
            fail("Expected RATE_LIMITED")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.RATE_LIMITED, e.code)
        }
    }

    @Test
    fun oversizeJsonCorruptApkSizeMismatchValidatorFailureAndCancellationCleanUpTmp() {
        val validApk = buildValidApkBytes(2048)

        server.route("/oversize-json") {
            val big = ByteArray(AppUpdater.MAX_JSON_BYTES + 1024) { ' '.code.toByte() }
            HttpResponse(200, body = big)
        }
        server.route("/not-a-zip") {
            val junk = "not-a-valid-apk-archive".toByteArray()
            HttpResponse(200, body = junk)
        }
        server.route("/valid-apk") {
            HttpResponse(200, body = validApk)
        }

        val updater = AppUpdater(
            latestUrl = "http://127.0.0.1:$port/oversize-json",
            listUrl = "",
            allowInsecureLoopback = true,
        )
        try {
            updater.checkLatestRelease("1.0.0")
            fail("Expected BAD_JSON on oversize body")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.BAD_JSON, e.code)
        }

        val dir = tempFolder.newFolder("tmp_cleanup")

        // Non-zip body rejected & .tmp removed
        val badDest = File(dir, "bad.apk")
        try {
            updater.downloadApk("http://127.0.0.1:$port/not-a-zip", badDest, -1L)
            fail("Expected CORRUPT_DOWNLOAD")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.CORRUPT_DOWNLOAD, e.code)
            assertFalse(badDest.exists())
            assertFalse(File(badDest.path + ".tmp").exists())
        }

        // Size mismatch rejected & .tmp removed
        val mismatchDest = File(dir, "mismatch.apk")
        try {
            updater.downloadApk("http://127.0.0.1:$port/valid-apk", mismatchDest, expectedSize = validApk.size + 99L)
            fail("Expected CORRUPT_DOWNLOAD on size mismatch")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.CORRUPT_DOWNLOAD, e.code)
            assertFalse(mismatchDest.exists())
            assertFalse(File(mismatchDest.path + ".tmp").exists())
        }

        // Validator failure (e.g. signature mismatch) deletes .tmp
        val sigDest = File(dir, "sig.apk")
        try {
            updater.downloadApk(
                url = "http://127.0.0.1:$port/valid-apk",
                dest = sigDest,
                expectedSize = validApk.size.toLong(),
                validator = { throw UpdateException(UpdateErrorCode.VERIFY_FAILED, "Signature mismatch") },
            )
            fail("Expected VERIFY_FAILED")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.VERIFY_FAILED, e.code)
            assertFalse(sigDest.exists())
            assertFalse(File(sigDest.path + ".tmp").exists())
        }

        // Cancellation mid-stream deletes .tmp
        val largeApk = buildValidApkBytes(512 * 1024)
        server.route("/large-apk") { HttpResponse(200, body = largeApk) }
        val cancelDest = File(dir, "cancel.apk")
        val token = CancelToken()
        try {
            updater.downloadApk(
                url = "http://127.0.0.1:$port/large-apk",
                dest = cancelDest,
                expectedSize = largeApk.size.toLong(),
                cancel = token,
                onProgress = { done, _ -> if (done > 0L) token.cancel() },
            )
            fail("Expected CANCELLED")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.CANCELLED, e.code)
            assertFalse(cancelDest.exists())
            assertFalse(File(cancelDest.path + ".tmp").exists())
        }
    }
}
