package com.minifile.explorer.core.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.zip.ZipFile

/**
 * Pure-JVM GitHub Releases updater (`ARCHITECTURE.md` §11.1).
 * Strictly enforces HTTPS, manual redirect cap (≤5 hops, no downgrade), 2 MiB JSON cap,
 * download size & ZIP/APK structure verification (`PK\x03\x04` + `AndroidManifest.xml`),
 * caller signature/package [validator], and atomic `.tmp` rename.
 */
class AppUpdater(
    private val latestUrl: String = DEFAULT_LATEST_URL,
    private val listUrl: String = DEFAULT_LIST_URL,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val allowInsecureLoopback: Boolean = false,
) {
    companion object {
        const val DEFAULT_LATEST_URL = "https://api.github.com/repos/codescv/minifile/releases/latest"
        const val DEFAULT_LIST_URL = "https://api.github.com/repos/codescv/minifile/releases?per_page=5"

        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_UNKNOWN_SIZE_BYTES = 512L * 1024 * 1024

        private const val PROGRESS_INTERVAL_MS = 100L
        private const val PROGRESS_INTERVAL_BYTES = 256L * 1024
    }

    fun checkLatestRelease(
        currentVersion: String,
        includePre: Boolean = false,
        cancel: CancelToken? = null,
        timeoutMs: Int = 15_000,
    ): UpdateCheckResult {
        val baseVersion = SemanticVersion.stripDebugSuffix(currentVersion) ?: currentVersion
        val headers = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to userAgent(baseVersion),
        )

        val latest: ReleaseJson = if (includePre) {
            val raw = fetchJson(listUrl, headers, cancel, timeoutMs)
            ReleaseParser.parseList(raw, includePre = true)
                ?: throw UpdateException(UpdateErrorCode.NOT_FOUND, "No releases found")
        } else {
            try {
                val raw = fetchJson(latestUrl, headers, cancel, timeoutMs)
                val parsed = ReleaseParser.parseLatest(raw)
                if (parsed.prerelease) {
                    val listRaw = fetchJson(listUrl, headers, cancel, timeoutMs)
                    ReleaseParser.parseList(listRaw, includePre = false)
                        ?: throw UpdateException(UpdateErrorCode.NOT_FOUND, "No stable release found")
                } else {
                    parsed
                }
            } catch (e: UpdateException) {
                if (e.code == UpdateErrorCode.NOT_FOUND && listUrl.isNotBlank()) {
                    val listRaw = fetchJson(listUrl, headers, cancel, timeoutMs)
                    ReleaseParser.parseList(listRaw, includePre = false)
                        ?: throw UpdateException(UpdateErrorCode.NOT_FOUND, "No stable release found", e)
                } else {
                    throw e
                }
            }
        }

        val apk = ReleaseParser.pickApk(latest, allowInsecureLoopback = allowInsecureLoopback)
        val newer = SemanticVersion.isNewerVersion(baseVersion, latest.tagName)
        return UpdateCheckResult(
            currentVersion = baseVersion,
            latest = latest,
            apk = apk,
            updateAvailable = newer,
        )
    }

    fun downloadApk(
        url: String,
        dest: File,
        expectedSize: Long = -1L,
        cancel: CancelToken? = null,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 60_000,
        validator: (File) -> Unit = {},
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        tmp.delete()

        val headers = mapOf(
            "Accept" to "application/octet-stream",
            "Accept-Encoding" to "identity",
            "User-Agent" to userAgent(null),
        )

        var conn: HttpURLConnection? = null
        var ok = false
        try {
            conn = openFollowingRedirects(url, headers, cancel, connectTimeoutMs, readTimeoutMs)
            val status = conn.responseCode
            if (status != 200) throw httpError(conn, status)

            val contentType = conn.contentType.orEmpty()
            if (contentType.trim().startsWith("text/html", ignoreCase = true)) {
                throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Server returned text/html instead of APK")
            }

            val contentLength = conn.contentLengthLong
            if (expectedSize > 0 && contentLength > 0 && contentLength != expectedSize) {
                throw UpdateException(
                    UpdateErrorCode.CORRUPT_DOWNLOAD,
                    "Content-Length ($contentLength) does not match expected asset size ($expectedSize)",
                )
            }

            val total = when {
                expectedSize > 0 -> expectedSize
                contentLength > 0 -> contentLength
                else -> -1L
            }

            val written = streamToFile(conn.inputStream, tmp, total, cancel, onProgress)
            verifyZipApk(tmp, written, total, expectedSize)
            validator(tmp)

            dest.delete()
            if (!tmp.renameTo(dest)) {
                throw UpdateException(UpdateErrorCode.NETWORK, "Failed to atomically rename APK to ${dest.name}")
            }
            ok = true
            return dest
        } catch (e: Exception) {
            throw mapError(e, cancel, readTimeoutMs)
        } finally {
            cancel?.detach()
            conn?.disconnect()
            if (!ok) {
                tmp.delete()
            }
        }
    }

    private fun fetchJson(
        url: String,
        headers: Map<String, String>,
        cancel: CancelToken?,
        timeoutMs: Int,
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = openFollowingRedirects(url, headers, cancel, timeoutMs, timeoutMs)
            val status = conn.responseCode
            if (status !in 200..299) throw httpError(conn, status)
            val text = readLimited(conn.inputStream, MAX_JSON_BYTES, cancel)
            if (cancel?.isCancelled == true) throw cancelled()
            return text
        } catch (e: Exception) {
            throw mapError(e, cancel, timeoutMs)
        } finally {
            cancel?.detach()
            conn?.disconnect()
        }
    }

    private fun streamToFile(
        input: InputStream,
        tmp: File,
        total: Long,
        cancel: CancelToken?,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var written = 0L
        var lastReportBytes = 0L
        var lastReportAt = 0L
        var reported = false
        input.use { ins ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (cancel?.isCancelled == true) throw cancelled()
                    val n = try {
                        ins.read(buf)
                    } catch (e: SocketTimeoutException) {
                        throw e
                    } catch (e: IOException) {
                        if (cancel?.isCancelled == true) throw cancelled()
                        throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Download interrupted at $written bytes", e)
                    }
                    if (n < 0) break
                    if (n == 0) continue

                    out.write(buf, 0, n)
                    written += n

                    if (total > 0 && written > total) {
                        throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Download exceeded expected size ($written > $total)")
                    }
                    if (total <= 0 && written > MAX_UNKNOWN_SIZE_BYTES) {
                        throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Download exceeded safety cap ($MAX_UNKNOWN_SIZE_BYTES bytes)")
                    }

                    val now = System.currentTimeMillis()
                    if (!reported || now - lastReportAt >= PROGRESS_INTERVAL_MS || written - lastReportBytes >= PROGRESS_INTERVAL_BYTES) {
                        onProgress(written, total)
                        reported = true
                        lastReportAt = now
                        lastReportBytes = written
                    }
                }
                out.flush()
                out.fd.sync()
            }
        }
        if (cancel?.isCancelled == true) throw cancelled()
        if (lastReportBytes != written || !reported) {
            onProgress(written, total)
        }
        return written
    }

    private fun verifyZipApk(
        tmp: File,
        written: Long,
        total: Long,
        expectedSize: Long,
    ) {
        if (total > 0 && written != total) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Incomplete download ($written / $total bytes)")
        }
        if (expectedSize > 0 && written != expectedSize) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Size mismatch ($written != $expectedSize bytes)")
        }
        val head = ByteArray(4)
        val n = if (written >= 4) tmp.inputStream().use { readFully(it, head) } else 0
        if (n < 4 || !head.contentEquals(APK_MAGIC)) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Missing APK/ZIP magic header")
        }
        val hasManifest = try {
            ZipFile(tmp).use { it.getEntry("AndroidManifest.xml") != null }
        } catch (e: Exception) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Corrupt ZIP archive", e)
        }
        if (!hasManifest) {
            throw UpdateException(UpdateErrorCode.CORRUPT_DOWNLOAD, "Archive does not contain AndroidManifest.xml")
        }
    }

    private fun openFollowingRedirects(
        url: String,
        headers: Map<String, String>,
        cancel: CancelToken?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        @Suppress("DEPRECATION")
        var current = try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw UpdateException(UpdateErrorCode.INSECURE_URL, "Invalid URL: $url", e)
        }
        validateAllowedUrl(current)

        var hops = 0
        while (true) {
            if (cancel?.isCancelled == true) throw cancelled()
            val conn = openConnection(current)
            var handedOff = false
            try {
                cancel?.attach { conn.disconnect() }
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = connectTimeoutMs.coerceAtLeast(1_000)
                conn.readTimeout = readTimeoutMs.coerceAtLeast(1_000)
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

                val status = conn.responseCode
                if (status !in REDIRECT_CODES) {
                    handedOff = true
                    return conn
                }

                val location = conn.getHeaderField("Location")?.trim()
                if (location.isNullOrEmpty()) {
                    throw UpdateException(UpdateErrorCode.NETWORK, "Redirect missing Location header (HTTP $status)", httpStatus = status)
                }
                if (++hops > MAX_REDIRECTS) {
                    throw UpdateException(UpdateErrorCode.NETWORK, "Too many redirects (>$MAX_REDIRECTS)")
                }

                @Suppress("DEPRECATION")
                val next = try {
                    URL(current, location)
                } catch (e: MalformedURLException) {
                    throw UpdateException(UpdateErrorCode.INSECURE_URL, "Invalid redirect Location: $location", e, status)
                }

                if (current.protocol.equals("https", ignoreCase = true) &&
                    next.protocol.equals("http", ignoreCase = true)
                ) {
                    throw UpdateException(UpdateErrorCode.INSECURE_URL, "Refusing https -> http redirect downgrade")
                }
                validateAllowedUrl(next)
                current = next
            } finally {
                if (!handedOff) {
                    cancel?.detach()
                    conn.disconnect()
                }
            }
        }
    }

    private fun validateAllowedUrl(url: URL) {
        val protocol = url.protocol?.lowercase().orEmpty()
        val host = url.host?.lowercase().orEmpty()
        if (host.isBlank()) {
            throw UpdateException(UpdateErrorCode.INSECURE_URL, "URL missing host: $url")
        }
        if (protocol == "https") return
        if (allowInsecureLoopback && protocol == "http" && (host == "127.0.0.1" || host == "localhost")) {
            return
        }
        throw UpdateException(UpdateErrorCode.INSECURE_URL, "Refusing non-HTTPS URL: $url")
    }

    private fun httpError(conn: HttpURLConnection, status: Int): UpdateException {
        val body = runCatching { conn.errorStream?.use { readPrefix(it, 4096) } }.getOrNull().orEmpty()
        val rateLimited = status == 429 || (
            status == 403 && (
                conn.getHeaderField("X-RateLimit-Remaining")?.trim() == "0" ||
                    body.contains("rate limit", ignoreCase = true)
                )
            )
        return when {
            rateLimited -> UpdateException(UpdateErrorCode.RATE_LIMITED, "GitHub API rate limit exceeded, try later", httpStatus = status)
            status == 404 -> UpdateException(UpdateErrorCode.NOT_FOUND, "Release not found (404)", httpStatus = 404)
            else -> UpdateException(UpdateErrorCode.NETWORK, "HTTP $status: ${body.trim().take(160)}", httpStatus = status)
        }
    }

    private fun mapError(e: Exception, cancel: CancelToken?, timeoutMs: Int): UpdateException {
        if (cancel?.isCancelled == true) {
            return if (e is UpdateException && e.code == UpdateErrorCode.CANCELLED) {
                e
            } else {
                UpdateException(UpdateErrorCode.CANCELLED, "Update cancelled", e)
            }
        }
        if (e is UpdateException) return e
        return when (e) {
            is SocketTimeoutException -> UpdateException(UpdateErrorCode.TIMEOUT, "Request timed out after $timeoutMs ms", e)
            is UnknownServiceException -> UpdateException(UpdateErrorCode.INSECURE_URL, "Cleartext HTTP not permitted", e)
            is UnknownHostException -> UpdateException(UpdateErrorCode.NETWORK, "Offline or DNS failure: ${e.message}", e)
            is IOException -> UpdateException(UpdateErrorCode.NETWORK, e.message ?: "Network error", e)
            else -> UpdateException(UpdateErrorCode.NETWORK, "${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun cancelled() = UpdateException(UpdateErrorCode.CANCELLED, "Update cancelled")

    private fun userAgent(version: String?): String {
        val clean = version?.trim()?.replace(Regex("[^0-9A-Za-z._-]"), "")?.takeIf { it.isNotEmpty() }
        return if (clean != null) "MiniFile/$clean (Android)" else "MiniFile (Android)"
    }

    private fun readLimited(input: InputStream, limit: Int, cancel: CancelToken?): String {
        input.use { ins ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                if (cancel?.isCancelled == true) throw cancelled()
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) {
                    throw UpdateException(UpdateErrorCode.BAD_JSON, "Release JSON exceeds $limit bytes")
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private fun readPrefix(input: InputStream, limit: Int): String {
        val buf = ByteArray(limit)
        val n = readFully(input, buf)
        return String(buf, 0, n, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}
