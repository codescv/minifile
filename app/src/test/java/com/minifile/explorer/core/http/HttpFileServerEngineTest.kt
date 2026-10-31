package com.minifile.explorer.core.http

import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipInputStream

class HttpFileServerEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private var server: HttpFileServerEngine? = null

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("http_root")
    }

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun startServer(
        readOnly: Boolean = false,
        showHidden: Boolean = false,
    ): HttpFileServerEngine {
        val srv = HttpFileServerEngine(
            config = HttpConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(rootDir.canonicalPath),
                readOnly = readOnly,
                showHidden = showHidden,
            ),
            fs = LocalFileSystem(),
        )
        srv.start()
        assertTrue(srv.isRunning)
        assertTrue(srv.boundPort > 0)
        server = srv
        return srv
    }

    private data class RawHttpResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    private fun sendRawHttp(
        port: Int,
        method: String,
        target: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): RawHttpResponse {
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 10_000
            val out = sock.getOutputStream()
            val reqHeaders = LinkedHashMap<String, String>()
            reqHeaders["Host"] = "127.0.0.1:$port"
            reqHeaders["Connection"] = "close"
            if (body != null && !headers.keys.any { it.equals("Content-Length", ignoreCase = true) } &&
                !headers.keys.any { it.equals("Transfer-Encoding", ignoreCase = true) }
            ) {
                reqHeaders["Content-Length"] = body.size.toString()
            }
            reqHeaders.putAll(headers)

            val sb = StringBuilder()
            sb.append("$method $target HTTP/1.1\r\n")
            for ((k, v) in reqHeaders) {
                sb.append("$k: $v\r\n")
            }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) {
                out.write(body)
            }
            out.flush()

            val ins = sock.getInputStream()
            val statusLine = readCrlfLine(ins) ?: error("Empty response from server")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: error("Invalid status line: $statusLine")

            val respHeaders = LinkedHashMap<String, String>()
            while (true) {
                val line = readCrlfLine(ins) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colon + 1).trim()
                    respHeaders[key] = value
                }
            }

            val respBody = ins.readBytes()
            return RawHttpResponse(status = status, headers = respHeaders, body = respBody)
        }
    }

    private fun readCrlfLine(ins: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sawAny) String(buf.toByteArray(), Charsets.ISO_8859_1) else null
            sawAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return String(buf.toByteArray(), Charsets.ISO_8859_1)
    }

    @Test
    fun browseRootAndSubfolder_rendersResponsiveHtmlBreadcrumbsAndEscapesHtml() {
        File(rootDir, "notes.txt").writeText("Hello HTTP Share")
        val sub = File(rootDir, "Photos & Trips").apply { mkdirs() }
        File(sub, "<xss>.jpg").writeBytes(ByteArray(128) { 0x42 })
        File(rootDir, ".secret_hidden").writeText("hidden")

        val srv = startServer(readOnly = false, showHidden = false)

        val rootResp = sendRawHttp(srv.boundPort, "GET", "/")
        assertEquals(200, rootResp.status)
        assertTrue(rootResp.headers["content-type"].orEmpty().contains("text/html"))
        assertTrue(rootResp.text.contains("notes.txt"))
        assertTrue(rootResp.text.contains("Photos &amp; Trips"))
        assertFalse("Hidden files should be excluded when showHidden=false", rootResp.text.contains(".secret_hidden"))
        assertTrue("Read-write mode must render New Folder & Upload UI", rootResp.text.contains("New Folder"))
        assertTrue("Read-write mode must render Upload Files UI", rootResp.text.contains("Upload"))

        val subResp = sendRawHttp(srv.boundPort, "GET", "/browse?path=%2FPhotos%20%26%20Trips")
        assertEquals(200, subResp.status)
        assertTrue("Filenames must be HTML-escaped", subResp.text.contains("&lt;xss&gt;.jpg"))
        assertFalse("Raw unescaped tag must never appear", subResp.text.contains("<xss>.jpg"))
    }

    @Test
    fun rawDownloadAndRangeRequests_supportFullAnd206PartialContentAnd416InvalidRange() {
        val payload = "abcdefghijklmnopqrstuvwxyz".toByteArray(Charsets.US_ASCII)
        File(rootDir, "alphabet.txt").writeBytes(payload)
        val pdfBytes = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF".toByteArray(Charsets.US_ASCII)
        File(rootDir, "manual.pdf").writeBytes(pdfBytes)

        val srv = startServer()

        // 1. Full GET /raw?path=/manual.pdf&download=1
        val fullPdf = sendRawHttp(srv.boundPort, "GET", "/raw?path=/manual.pdf&download=1")
        assertEquals(200, fullPdf.status)
        assertEquals("application/pdf", fullPdf.headers["content-type"])
        assertEquals(pdfBytes.size.toString(), fullPdf.headers["content-length"])
        assertEquals("bytes", fullPdf.headers["accept-ranges"])
        assertTrue(fullPdf.headers["content-disposition"].orEmpty().startsWith("attachment;"))
        assertArrayEquals(pdfBytes, fullPdf.body)

        // 2. HEAD /raw?path=/alphabet.txt
        val headResp = sendRawHttp(srv.boundPort, "HEAD", "/raw?path=/alphabet.txt")
        assertEquals(200, headResp.status)
        assertEquals("26", headResp.headers["content-length"])
        assertEquals(0, headResp.body.size)

        // 3. Explicit byte range bytes=4-9 -> "efghij"
        val rangeMid = sendRawHttp(
            port = srv.boundPort,
            method = "GET",
            target = "/raw?path=/alphabet.txt",
            headers = mapOf("Range" to "bytes=4-9"),
        )
        assertEquals(206, rangeMid.status)
        assertEquals("bytes 4-9/26", rangeMid.headers["content-range"])
        assertEquals("6", rangeMid.headers["content-length"])
        assertEquals("efghij", rangeMid.text)

        // 4. Open-ended range bytes=20- -> "uvwxyz"
        val rangeOpen = sendRawHttp(
            port = srv.boundPort,
            method = "GET",
            target = "/raw?path=/alphabet.txt",
            headers = mapOf("Range" to "bytes=20-"),
        )
        assertEquals(206, rangeOpen.status)
        assertEquals("bytes 20-25/26", rangeOpen.headers["content-range"])
        assertEquals("uvwxyz", rangeOpen.text)

        // 5. Suffix range bytes=-4 -> "wxyz"
        val rangeSuffix = sendRawHttp(
            port = srv.boundPort,
            method = "GET",
            target = "/raw?path=/alphabet.txt",
            headers = mapOf("Range" to "bytes=-4"),
        )
        assertEquals(206, rangeSuffix.status)
        assertEquals("bytes 22-25/26", rangeSuffix.headers["content-range"])
        assertEquals("wxyz", rangeSuffix.text)

        // 6. Unsatisfiable range bytes=100-200 -> 416
        val badRange = sendRawHttp(
            port = srv.boundPort,
            method = "GET",
            target = "/raw?path=/alphabet.txt",
            headers = mapOf("Range" to "bytes=100-200"),
        )
        assertEquals(416, badRange.status)
        assertEquals("bytes */26", badRange.headers["content-range"])
    }

    @Test
    fun zipEndpoint_streamsDirectoryTreeOnTheFlyAndSkipsSymlinks() {
        val docs = File(rootDir, "ProjectDocs").apply { mkdirs() }
        File(docs, "readme.md").writeText("# Project Docs\nLine 2")
        val sub = File(docs, "specs").apply { mkdirs() }
        val binData = ByteArray(4096) { idx -> (idx * 31 and 0xFF).toByte() }
        File(sub, "data.bin").writeBytes(binData)

        // Create outside secret file + symlink inside docs -> must be excluded from zip
        val outsideSecret = tempFolder.newFile("outside_secret.txt").apply {
            writeText("TOP SECRET OUTSIDE JAIL")
        }
        runCatching {
            Files.createSymbolicLink(
                File(docs, "escape_link.txt").toPath(),
                outsideSecret.toPath(),
            )
        }

        val srv = startServer()
        val zipResp = sendRawHttp(srv.boundPort, "GET", "/zip?path=/ProjectDocs")
        assertEquals(200, zipResp.status)
        assertEquals("application/zip", zipResp.headers["content-type"])
        assertTrue(zipResp.headers["content-disposition"].orEmpty().contains("ProjectDocs.zip"))

        val extracted = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipResp.body)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    extracted[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
            }
        }

        assertEquals(setOf("ProjectDocs/readme.md", "ProjectDocs/specs/data.bin"), extracted.keys)
        assertEquals("# Project Docs\nLine 2", String(extracted.getValue("ProjectDocs/readme.md"), Charsets.UTF_8))
        assertArrayEquals(binData, extracted.getValue("ProjectDocs/specs/data.bin"))
        assertFalse(extracted.keys.any { it.contains("escape_link") })
    }

    @Test
    fun mkdirAndUpload_supportRawOctetStreamChunkedAndMultipartUploads() {
        val srv = startServer(readOnly = false)

        // 1. POST /mkdir?path=/&name=Uploads
        val mkdirResp = sendRawHttp(srv.boundPort, "POST", "/mkdir?path=/&name=Uploads")
        assertEquals(201, mkdirResp.status)
        val uploadsDir = File(rootDir, "Uploads")
        assertTrue(uploadsDir.isDirectory)

        // 2. POST /upload?path=/Uploads&name=binary.dat (raw application/octet-stream with all 256 byte values)
        val rawBinary = ByteArray(10_000) { i -> (i and 0xFF).toByte() }
        val rawUploadResp = sendRawHttp(
            port = srv.boundPort,
            method = "POST",
            target = "/upload?path=/Uploads&name=binary.dat",
            headers = mapOf("Content-Type" to "application/octet-stream"),
            body = rawBinary,
        )
        assertEquals(201, rawUploadResp.status)
        assertArrayEquals(rawBinary, File(uploadsDir, "binary.dat").readBytes())

        // 3. POST /upload with Transfer-Encoding: chunked via HttpURLConnection
        val chunkedPayload = "Chunked stream payload ".repeat(500).toByteArray(Charsets.UTF_8)
        val conn = (URL("http://127.0.0.1:${srv.boundPort}/upload?path=/Uploads&name=chunked.txt").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setChunkedStreamingMode(256)
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        conn.outputStream.use { it.write(chunkedPayload) }
        assertEquals(201, conn.responseCode)
        conn.disconnect()
        assertArrayEquals(chunkedPayload, File(uploadsDir, "chunked.txt").readBytes())

        // 4. POST /upload multipart/form-data with multiple files
        val boundary = "----MiniFileBoundary987654321"
        val file1Bytes = "First multipart file content".toByteArray(Charsets.UTF_8)
        val file2Bytes = ByteArray(512) { idx -> (255 - (idx and 0xFF)).toByte() }

        val mpOut = ByteArrayOutputStream()
        fun writeAscii(s: String) = mpOut.write(s.toByteArray(Charsets.UTF_8))
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"files\"; filename=\"hello.txt\"\r\n")
        writeAscii("Content-Type: text/plain\r\n\r\n")
        mpOut.write(file1Bytes)
        writeAscii("\r\n--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"files\"; filename=\"bytes.bin\"\r\n")
        writeAscii("Content-Type: application/octet-stream\r\n\r\n")
        mpOut.write(file2Bytes)
        writeAscii("\r\n--$boundary--\r\n")

        val mpResp = sendRawHttp(
            port = srv.boundPort,
            method = "POST",
            target = "/upload?path=/Uploads",
            headers = mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
            body = mpOut.toByteArray(),
        )
        assertEquals(201, mpResp.status)
        assertArrayEquals(file1Bytes, File(uploadsDir, "hello.txt").readBytes())
        assertArrayEquals(file2Bytes, File(uploadsDir, "bytes.bin").readBytes())
    }

    @Test
    fun readOnlyMode_blocksMkdirAndUploadWith403WhileAllowingReads() {
        File(rootDir, "readonly.txt").writeText("safe to read")
        val srv = startServer(readOnly = true)

        val browseResp = sendRawHttp(srv.boundPort, "GET", "/")
        assertEquals(200, browseResp.status)
        assertTrue(browseResp.text.contains("Read-only"))

        val rawResp = sendRawHttp(srv.boundPort, "GET", "/raw?path=/readonly.txt")
        assertEquals(200, rawResp.status)
        assertEquals("safe to read", rawResp.text)

        val mkdirResp = sendRawHttp(srv.boundPort, "POST", "/mkdir?path=/&name=ForbiddenDir")
        assertEquals(403, mkdirResp.status)
        assertFalse(File(rootDir, "ForbiddenDir").exists())

        val uploadResp = sendRawHttp(
            port = srv.boundPort,
            method = "POST",
            target = "/upload?path=/&name=forbidden.txt",
            headers = mapOf("Content-Type" to "application/octet-stream"),
            body = "nope".toByteArray(Charsets.UTF_8),
        )
        assertEquals(403, uploadResp.status)
        assertFalse(File(rootDir, "forbidden.txt").exists())
    }

    @Test
    fun pathTraversalAndSymlinkEscape_rejectedWith403AcrossAllEndpoints() {
        val outsideFile = tempFolder.newFile("secret_outside.txt").apply {
            writeText("DO NOT LEAK")
        }
        val outsideDir = tempFolder.newFolder("outside_dir").apply {
            File(this, "nested.txt").writeText("OUTSIDE DIR")
        }

        val symlinkFile = File(rootDir, "sym_secret.txt")
        val symlinkDir = File(rootDir, "sym_dir")
        val symlinksCreated = runCatching {
            Files.createSymbolicLink(symlinkFile.toPath(), outsideFile.toPath())
            Files.createSymbolicLink(symlinkDir.toPath(), outsideDir.toPath())
            true
        }.getOrDefault(false)

        val srv = startServer(readOnly = false)

        val traversalTargets = listOf(
            "GET" to "/browse?path=..",
            "GET" to "/browse?path=/../../etc",
            "GET" to "/raw?path=../secret_outside.txt",
            "GET" to "/raw?path=%2e%2e%2fsecret_outside.txt",
            "GET" to "/raw?path=/sub/../../secret_outside.txt",
            "GET" to "/zip?path=..",
            "GET" to "/../../secret_outside.txt",
            "POST" to "/mkdir?path=..&name=escape",
            "POST" to "/mkdir?path=/&name=../escape",
            "POST" to "/upload?path=..&name=evil.txt",
            "POST" to "/upload?path=/&name=../evil.txt",
        )

        for ((method, target) in traversalTargets) {
            val body = if (method == "POST") "x".toByteArray() else null
            val resp = sendRawHttp(srv.boundPort, method, target, body = body)
            assertEquals("Expected 403 Forbidden for $method $target", 403, resp.status)
        }

        if (symlinksCreated) {
            assertNotNull(Files.readSymbolicLink(symlinkFile.toPath()))
            assertEquals(403, sendRawHttp(srv.boundPort, "GET", "/raw?path=/sym_secret.txt").status)
            assertEquals(403, sendRawHttp(srv.boundPort, "GET", "/browse?path=/sym_dir").status)
            assertEquals(403, sendRawHttp(srv.boundPort, "GET", "/zip?path=/sym_dir").status)
            assertEquals(
                403,
                sendRawHttp(
                    srv.boundPort,
                    "POST",
                    "/upload?path=/sym_dir&name=injected.txt",
                    body = "bad".toByteArray(),
                ).status,
            )
            assertFalse(File(outsideDir, "injected.txt").exists())
        }
    }
}
