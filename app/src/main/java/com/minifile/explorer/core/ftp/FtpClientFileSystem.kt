package com.minifile.explorer.core.ftp

import com.minifile.explorer.core.model.FtpServerBookmark
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-Kotlin RFC 959 / RFC 2428 (`EPSV`) / RFC 3659 (`MLSD`, `MLST`, `SIZE`, `MDTM`, `REST`)
 * FTP client implementation of [FileSystem] for [VfsPath.Ftp].
 *
 * Contains zero `android.*`, `dalvik.*`, or `androidx.*` imports.
 */
class FtpClientFileSystem(
    private val serverLookup: (String) -> FtpServerBookmark?,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 15_000,
) : FileSystem {

    override fun supports(path: VfsPath): Boolean = path is VfsPath.Ftp

    private fun requireFtp(path: VfsPath): VfsPath.Ftp =
        path as? VfsPath.Ftp ?: throw IllegalArgumentException("Expected FTP path, got $path")

    override suspend fun child(dir: VfsPath, name: String): VfsPath {
        val ftp = requireFtp(dir)
        val clean = name.trim('/')
        if (clean.isEmpty()) return ftp
        val joined = if (ftp.normalizedRemotePath == "/") {
            "/$clean"
        } else {
            "${ftp.normalizedRemotePath}/$clean"
        }
        return VfsPath.Ftp(ftp.serverId, PathUtils.normalizeLocalPath(joined))
    }

    override suspend fun parent(path: VfsPath): VfsPath? {
        val ftp = requireFtp(path)
        val norm = ftp.normalizedRemotePath
        if (norm == "/") return null
        val slash = norm.lastIndexOf('/')
        val parentRemote = if (slash <= 0) "/" else norm.substring(0, slash)
        return VfsPath.Ftp(ftp.serverId, parentRemote)
    }

    override suspend fun exists(path: VfsPath): Boolean = stat(path) != null

    override suspend fun stat(path: VfsPath): VfsNode? {
        val ftp = requireFtp(path)
        return withSession(ftp.serverId) { session ->
            statInSession(session, ftp)
        }
    }

    override suspend fun list(dir: VfsPath, includeHidden: Boolean): List<VfsNode> {
        val ftp = requireFtp(dir)
        return withSession(ftp.serverId) { session ->
            listInSession(session, ftp, includeHidden)
        }
    }

    override suspend fun createDirectory(dir: VfsPath, name: String): VfsPath {
        val ftpDir = requireFtp(dir)
        val target = child(ftpDir, name) as VfsPath.Ftp
        withSession(ftpDir.serverId) { session ->
            val reply = session.command("MKD ${target.normalizedRemotePath}")
            if (reply.code !in 200..299) {
                throw IOException("FTP MKD failed (${reply.code}): ${reply.message}")
            }
        }
        return target
    }

    override suspend fun createFile(dir: VfsPath, name: String, mime: String): VfsPath {
        val ftpDir = requireFtp(dir)
        val target = child(ftpDir, name) as VfsPath.Ftp
        withSession(ftpDir.serverId) { session ->
            val dataSock = openPassiveDataSocket(session)
            try {
                val startReply = session.command("STOR ${target.normalizedRemotePath}")
                if (startReply.code !in 100..199) {
                    throw IOException("FTP STOR failed (${startReply.code}): ${startReply.message}")
                }
            } catch (t: Throwable) {
                runCatching { dataSock.close() }
                throw t
            }
            dataSock.close()
            val doneReply = session.readReply()
            if (doneReply.code !in 200..299) {
                throw IOException("FTP STOR completion failed (${doneReply.code}): ${doneReply.message}")
            }
        }
        return target
    }

    override suspend fun delete(path: VfsPath): Boolean {
        val ftp = requireFtp(path)
        if (ftp.normalizedRemotePath == "/") return false
        return withSession(ftp.serverId) { session ->
            val node = statInSession(session, ftp) ?: return@withSession false
            deleteRecursiveInSession(session, node)
        }
    }

    private fun deleteRecursiveInSession(session: ClientSession, node: VfsNode): Boolean {
        val ftpPath = node.path as? VfsPath.Ftp ?: return false
        if (node.isDirectory && !node.isSymlink) {
            val children = runCatching {
                listInSession(session, ftpPath, includeHidden = true)
            }.getOrNull() ?: return false
            for (childNode in children) {
                if (!deleteRecursiveInSession(session, childNode)) {
                    return false
                }
            }
            val rmd = session.command("RMD ${ftpPath.normalizedRemotePath}")
            return rmd.code in 200..299
        } else {
            val dele = session.command("DELE ${ftpPath.normalizedRemotePath}")
            return dele.code in 200..299
        }
    }

    override suspend fun rename(path: VfsPath, newName: String): VfsPath {
        val ftp = requireFtp(path)
        val parentFtp = parent(ftp) as? VfsPath.Ftp
            ?: throw IOException("Cannot rename FTP root directory")
        val target = child(parentFtp, newName) as VfsPath.Ftp
        withSession(ftp.serverId) { session ->
            val rnfr = session.command("RNFR ${ftp.normalizedRemotePath}")
            if (rnfr.code !in 300..399) {
                throw IOException("FTP RNFR failed (${rnfr.code}): ${rnfr.message}")
            }
            val rnto = session.command("RNTO ${target.normalizedRemotePath}")
            if (rnto.code !in 200..299) {
                throw IOException("FTP RNTO failed (${rnto.code}): ${rnto.message}")
            }
        }
        return target
    }

    override suspend fun moveWithinSameBackend(src: VfsPath, dstDir: VfsPath): VfsPath? {
        val srcFtp = src as? VfsPath.Ftp ?: return null
        val dstFtpDir = dstDir as? VfsPath.Ftp ?: return null
        if (srcFtp.serverId != dstFtpDir.serverId) return null
        if (srcFtp.normalizedRemotePath == "/") return null

        val dstTarget = child(dstFtpDir, srcFtp.displayName) as VfsPath.Ftp
        return runCatching {
            withSession(srcFtp.serverId) { session ->
                val rnfr = session.command("RNFR ${srcFtp.normalizedRemotePath}")
                if (rnfr.code !in 300..399) return@withSession null
                val rnto = session.command("RNTO ${dstTarget.normalizedRemotePath}")
                if (rnto.code !in 200..299) return@withSession null
                dstTarget
            }
        }.getOrNull()
    }

    override fun openInput(path: VfsPath, offset: Long): InputStream {
        val ftp = requireFtp(path)
        val session = openSession(ftp.serverId)
        var dataSock: Socket? = null
        try {
            dataSock = openPassiveDataSocket(session)
            if (offset > 0L) {
                val rest = session.command("REST $offset")
                if (rest.code !in 300..399) {
                    throw IOException("FTP REST rejected (${rest.code}): ${rest.message}")
                }
            }
            val retr = session.command("RETR ${ftp.normalizedRemotePath}")
            if (retr.code !in 100..199) {
                throw FileNotFoundException("FTP RETR failed (${retr.code}): ${retr.message}")
            }
            val rawIn = BufferedInputStream(dataSock.getInputStream(), 64 * 1024)
            return FtpDataInputStream(rawIn, dataSock, session)
        } catch (t: Throwable) {
            runCatching { dataSock?.close() }
            runCatching { session.close() }
            throw (t as? IOException) ?: IOException(t.message ?: "FTP openInput failed", t)
        }
    }

    override fun openOutput(path: VfsPath, append: Boolean): OutputStream {
        val ftp = requireFtp(path)
        val session = openSession(ftp.serverId)
        var dataSock: Socket? = null
        try {
            dataSock = openPassiveDataSocket(session)
            val verb = if (append) "APPE" else "STOR"
            val start = session.command("$verb ${ftp.normalizedRemotePath}")
            if (start.code !in 100..199) {
                throw IOException("FTP $verb failed (${start.code}): ${start.message}")
            }
            val rawOut = BufferedOutputStream(dataSock.getOutputStream(), 64 * 1024)
            return FtpDataOutputStream(rawOut, dataSock, session, verb)
        } catch (t: Throwable) {
            runCatching { dataSock?.close() }
            runCatching { session.close() }
            throw (t as? IOException) ?: IOException(t.message ?: "FTP openOutput failed", t)
        }
    }

    override suspend fun setLastModified(path: VfsPath, epochMs: Long): Boolean {
        val ftp = requireFtp(path)
        if (epochMs <= 0L) return false
        val stamp = MLSD_UTC_FMT.format(Instant.ofEpochMilli(epochMs))
        return runCatching {
            withSession(ftp.serverId) { session ->
                val reply = session.command("MFMT $stamp ${ftp.normalizedRemotePath}")
                reply.code in 200..299
            }
        }.getOrDefault(false)
    }

    override suspend fun space(path: VfsPath): Pair<Long, Long>? = null

    private fun statInSession(session: ClientSession, ftp: VfsPath.Ftp): VfsNode? {
        val remote = ftp.normalizedRemotePath
        val mlst = session.command("MLST $remote")
        if (mlst.code in 200..299) {
            for (line in mlst.lines) {
                val trimmedStart = if (line.startsWith(" ")) line.substring(1) else line
                if (trimmedStart.contains(';') && trimmedStart.contains('=')) {
                    val parsed = parseMlsdEntry(
                        parentDir = null,
                        explicitPath = ftp,
                        entryLine = trimmedStart,
                    )
                    if (parsed != null) return parsed
                }
            }
            if (remote == "/") {
                return VfsNode(
                    path = ftp,
                    name = "/",
                    isDirectory = true,
                    size = 0L,
                    lastModified = 0L,
                )
            }
        } else if (mlst.code == 550) {
            return null
        }

        if (remote == "/") {
            val cwd = session.command("CWD /")
            if (cwd.code in 500..599 && cwd.code != 502) return null
            return VfsNode(
                path = ftp,
                name = "/",
                isDirectory = true,
                size = 0L,
                lastModified = 0L,
            )
        }

        val slash = remote.lastIndexOf('/')
        val parentRemote = if (slash <= 0) "/" else remote.substring(0, slash)
        val parentFtp = VfsPath.Ftp(ftp.serverId, parentRemote)
        val siblings = runCatching {
            listInSession(session, parentFtp, includeHidden = true)
        }.getOrNull()
        if (siblings != null) {
            return siblings.firstOrNull { it.name == ftp.displayName }
        }

        val cwd = session.command("CWD $remote")
        if (cwd.code in 200..299) {
            return VfsNode(
                path = ftp,
                name = ftp.displayName,
                isDirectory = true,
                size = 0L,
                lastModified = 0L,
            )
        }

        val szReply = session.command("SIZE $remote")
        if (szReply.code == 213) {
            val size = szReply.message.trim().toLongOrNull() ?: 0L
            val mdtmReply = session.command("MDTM $remote")
            val mtime = if (mdtmReply.code == 213) parseMlsdTimestamp(mdtmReply.message.trim()) else 0L
            return VfsNode(
                path = ftp,
                name = ftp.displayName,
                isDirectory = false,
                size = size,
                lastModified = mtime,
            )
        }
        return null
    }

    private fun listInSession(
        session: ClientSession,
        dir: VfsPath.Ftp,
        includeHidden: Boolean,
    ): List<VfsNode> {
        val remoteDir = dir.normalizedRemotePath
        val cwdReply = session.command("CWD $remoteDir")
        val useCurrentDir = when {
            cwdReply.code in 200..299 -> true
            cwdReply.code in 550..559 -> throw FileNotFoundException("FTP directory not found: $remoteDir")
            else -> false
        }

        val mlsdCmd = if (useCurrentDir) "MLSD" else "MLSD $remoteDir"
        val mlsdLines = tryReadDataLines(session, mlsdCmd)
        val nodes = if (mlsdLines != null) {
            mlsdLines.mapNotNull { line ->
                parseMlsdEntry(parentDir = dir, explicitPath = null, entryLine = line)
            }
        } else {
            val listCmd = if (useCurrentDir) "LIST" else "LIST $remoteDir"
            val listLines = readDataLinesOrThrow(session, listCmd)
            listLines.mapNotNull { line -> parseListLine(dir, line) }
        }

        val hasNomedia = nodes.any { !it.isDirectory && it.name == ".nomedia" }
        val filtered = if (includeHidden) nodes else nodes.filterNot { it.isHidden }
        // Note: directory child entries themselves default to hasNomedia=false; root stat handles currentDir.
        @Suppress("UNUSED_VARIABLE")
        val unused = hasNomedia
        return filtered
    }

    /**
     * Attempts [cmd] over a passive data socket. Returns `null` if the server replies that the
     * command is unrecognized / unsupported (500, 501, 502, 504), or throws [IOException] on real
     * path/network errors (e.g. 550, 425).
     */
    private fun tryReadDataLines(session: ClientSession, cmd: String): List<String>? {
        val dataSock = openPassiveDataSocket(session)
        val startReply = try {
            session.command(cmd)
        } catch (t: Throwable) {
            runCatching { dataSock.close() }
            throw t
        }
        if (startReply.code !in 100..199) {
            runCatching { dataSock.close() }
            if (startReply.code in setOf(500, 501, 502, 504)) {
                return null
            }
            if (startReply.code == 550) {
                throw FileNotFoundException("FTP path not found (${startReply.code}): ${startReply.message}")
            }
            return null
        }

        val lines = try {
            dataSock.getInputStream().bufferedReader(Charsets.UTF_8).useLines { seq ->
                seq.toList()
            }
        } finally {
            runCatching { dataSock.close() }
        }
        val doneReply = session.readReply()
        if (doneReply.code !in 200..299) {
            throw IOException("FTP data command '$cmd' failed (${doneReply.code}): ${doneReply.message}")
        }
        return lines
    }

    private fun readDataLinesOrThrow(session: ClientSession, cmd: String): List<String> {
        val dataSock = openPassiveDataSocket(session)
        val startReply = try {
            session.command(cmd)
        } catch (t: Throwable) {
            runCatching { dataSock.close() }
            throw t
        }
        if (startReply.code !in 100..199) {
            runCatching { dataSock.close() }
            if (startReply.code == 550) {
                throw FileNotFoundException("FTP path not found (${startReply.code}): ${startReply.message}")
            }
            throw IOException("FTP '$cmd' failed (${startReply.code}): ${startReply.message}")
        }

        val lines = try {
            dataSock.getInputStream().bufferedReader(Charsets.UTF_8).useLines { seq ->
                seq.toList()
            }
        } finally {
            runCatching { dataSock.close() }
        }
        val doneReply = session.readReply()
        if (doneReply.code !in 200..299) {
            throw IOException("FTP '$cmd' completion failed (${doneReply.code}): ${doneReply.message}")
        }
        return lines
    }

    private inline fun <T> withSession(serverId: String, block: (ClientSession) -> T): T {
        val session = openSession(serverId)
        return try {
            val result = block(session)
            runCatching { session.sendQuitBestEffort() }
            result
        } finally {
            session.close()
        }
    }

    private fun openSession(serverId: String): ClientSession {
        val server = serverLookup(serverId)
            ?: throw FileNotFoundException("FTP server configuration not found: $serverId")
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(server.host, server.port), connectTimeoutMs)
            sock.soTimeout = readTimeoutMs
            sock.tcpNoDelay = true
        } catch (t: Throwable) {
            runCatching { sock.close() }
            throw (t as? IOException)
                ?: IOException("Unable to connect to ${server.host}:${server.port}: ${t.message}", t)
        }

        val session = ClientSession(sock)
        try {
            var greeting = session.readReply()
            while (greeting.code in 100..199) {
                greeting = session.readReply()
            }
            if (greeting.code !in 200..299) {
                throw IOException("FTP server rejected greeting (${greeting.code}): ${greeting.message}")
            }

            val user = if (server.anonymous) "anonymous" else server.username.ifBlank { "anonymous" }
            val pass = if (server.anonymous) "anonymous@" else server.password
            val userReply = session.command("USER $user")
            when (userReply.code) {
                in 200..299 -> Unit
                331, 332 -> {
                    val passReply = session.command("PASS $pass")
                    if (passReply.code !in 200..299) {
                        throw IOException("FTP login failed (${passReply.code}): ${passReply.message}")
                    }
                }
                else -> throw IOException("FTP USER rejected (${userReply.code}): ${userReply.message}")
            }

            // Best-effort UTF-8 negotiation + Binary image mode
            session.command("OPTS UTF8 ON")
            val typeReply = session.command("TYPE I")
            if (typeReply.code !in 200..299) {
                throw IOException("FTP TYPE I rejected (${typeReply.code}): ${typeReply.message}")
            }
            return session
        } catch (t: Throwable) {
            session.close()
            throw (t as? IOException) ?: IOException(t.message ?: "FTP handshake failed", t)
        }
    }

    private fun openPassiveDataSocket(session: ClientSession): Socket {
        val epsv = session.command("EPSV")
        if (epsv.code == 229) {
            val epsvPort = parseEpsvPort(epsv.message)
            if (epsvPort != null) {
                return connectDataSocket(session.controlSocket.inetAddress, epsvPort)
            }
        }

        val pasv = session.command("PASV")
        if (pasv.code != 227) {
            throw IOException("FTP passive mode failed (${pasv.code}): ${pasv.message}")
        }
        val pasvPort = parsePasvPort(pasv.message)
            ?: throw IOException("Malformed FTP PASV reply: ${pasv.message}")
        return connectDataSocket(session.controlSocket.inetAddress, pasvPort)
    }

    private fun connectDataSocket(peerAddress: InetAddress, port: Int): Socket {
        val dataSock = Socket()
        try {
            dataSock.connect(InetSocketAddress(peerAddress, port), connectTimeoutMs)
            dataSock.soTimeout = readTimeoutMs
            dataSock.tcpNoDelay = true
            return dataSock
        } catch (t: Throwable) {
            runCatching { dataSock.close() }
            throw (t as? IOException) ?: IOException("Failed to open FTP data socket on port $port", t)
        }
    }

    private data class FtpReply(
        val code: Int,
        val message: String,
        val lines: List<String>,
    )

    private class ClientSession(val controlSocket: Socket) : Closeable {
        private val reader = BufferedReader(InputStreamReader(controlSocket.getInputStream(), Charsets.UTF_8))
        private val writer = OutputStreamWriter(BufferedOutputStream(controlSocket.getOutputStream()), Charsets.UTF_8)
        private val closed = AtomicBoolean(false)

        fun command(cmd: String): FtpReply {
            if (cmd.indexOf('\r') >= 0 || cmd.indexOf('\n') >= 0) {
                throw IllegalArgumentException("Illegal CR/LF in FTP command")
            }
            writer.write(cmd)
            writer.write("\r\n")
            writer.flush()
            return readReply()
        }

        fun readReply(): FtpReply {
            val first = reader.readLine() ?: throw EOFException("FTP server closed control connection")
            if (first.length < 3 || !first[0].isDigit() || !first[1].isDigit() || !first[2].isDigit()) {
                throw IOException("Malformed FTP reply: $first")
            }
            val codeStr = first.substring(0, 3)
            val code = codeStr.toInt()
            if (first.length > 3 && first[3] == '-') {
                val collected = ArrayList<String>()
                collected.add(first.substring(4))
                while (true) {
                    val line = reader.readLine()
                        ?: throw EOFException("Unexpected EOF during multiline FTP reply")
                    if (line.startsWith("$codeStr ") || line == codeStr) {
                        val footer = if (line.length > 4) line.substring(4) else ""
                        return FtpReply(
                            code = code,
                            message = footer.ifEmpty { collected.firstOrNull().orEmpty() },
                            lines = collected,
                        )
                    }
                    collected.add(line)
                }
            }
            val msg = if (first.length > 4) first.substring(4) else ""
            return FtpReply(code = code, message = msg, lines = listOf(msg))
        }

        fun sendQuitBestEffort() {
            if (closed.get() || controlSocket.isClosed) return
            controlSocket.soTimeout = 1_500
            writer.write("QUIT\r\n")
            writer.flush()
            runCatching { readReply() }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { controlSocket.close() }
        }
    }

    private class FtpDataInputStream(
        private val delegate: InputStream,
        private val dataSocket: Socket,
        private val session: ClientSession,
    ) : InputStream() {
        private val closed = AtomicBoolean(false)

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun available(): Int = delegate.available()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { delegate.close() }
            runCatching { dataSocket.close() }
            runCatching {
                session.controlSocket.soTimeout = 2_000
                session.readReply()
                session.sendQuitBestEffort()
            }
            session.close()
        }
    }

    private class FtpDataOutputStream(
        private val delegate: OutputStream,
        private val dataSocket: Socket,
        private val session: ClientSession,
        private val verb: String,
    ) : OutputStream() {
        private val closed = AtomicBoolean(false)

        override fun write(b: Int) {
            delegate.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            var flushError: Throwable? = null
            try {
                delegate.flush()
                delegate.close()
            } catch (t: Throwable) {
                flushError = t
            } finally {
                runCatching { dataSocket.close() }
            }

            try {
                if (flushError != null) {
                    throw (flushError as? IOException) ?: IOException("FTP write failed", flushError)
                }
                val done = session.readReply()
                if (done.code !in 200..299) {
                    throw IOException("FTP $verb completion failed (${done.code}): ${done.message}")
                }
                runCatching { session.sendQuitBestEffort() }
            } finally {
                session.close()
            }
        }
    }

    companion object {
        private val PASV_REGEX = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""")
        private val UNIX_LIST_REGEX = Regex(
            """^([bcdlps-][rwxStTs-]{9}[+@.]?)\s+\d+\s+\S+\s+\S+\s+(\d+)\s+([A-Za-z]{3}\s+\d{1,2}\s+(?:\d{1,2}:\d{2}|\d{4}))\s+(.+)$""",
        )
        private val DOS_LIST_REGEX = Regex(
            """^(\d{2}-\d{2}-\d{2,4})\s+(\d{1,2}:\d{2}(?:AM|PM)?)\s+(<DIR>|\d+)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val MLSD_UTC_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US).withZone(ZoneOffset.UTC)
        private val MLSD_LOCAL_PARSE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US)

        internal fun parseEpsvPort(message: String): Int? {
            val open = message.lastIndexOf('(')
            val close = message.indexOf(')', startIndex = (open + 1).coerceAtLeast(0))
            if (open < 0 || close <= open + 4) return null
            val inner = message.substring(open + 1, close)
            val delim = inner[0]
            val parts = inner.split(delim)
            if (parts.size < 5) return null
            return parts[3].trim().toIntOrNull()?.takeIf { it in 1..65535 }
        }

        internal fun parsePasvPort(message: String): Int? {
            val m = PASV_REGEX.find(message) ?: return null
            val p1 = m.groupValues[5].toIntOrNull() ?: return null
            val p2 = m.groupValues[6].toIntOrNull() ?: return null
            if (p1 !in 0..255 || p2 !in 0..255) return null
            val port = (p1 shl 8) or p2
            return port.takeIf { it in 1..65535 }
        }

        internal fun parseMlsdTimestamp(raw: String): Long {
            val trimmed = raw.trim()
            if (trimmed.length < 14) return 0L
            val main14 = trimmed.substring(0, 14)
            if (!main14.all { it.isDigit() }) return 0L
            return runCatching {
                val baseMs = LocalDateTime.parse(main14, MLSD_LOCAL_PARSE_FMT)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
                val fracMs = if (trimmed.length > 15 && trimmed[14] == '.') {
                    val digits = trimmed.substring(15).takeWhile { it.isDigit() }.take(3).padEnd(3, '0')
                    digits.toLongOrNull() ?: 0L
                } else {
                    0L
                }
                baseMs + fracMs
            }.getOrDefault(0L)
        }

        internal fun parseMlsdEntry(
            parentDir: VfsPath.Ftp?,
            explicitPath: VfsPath.Ftp?,
            entryLine: String,
        ): VfsNode? {
            if (entryLine.isBlank()) return null
            val sp = entryLine.indexOf(' ')
            if (sp <= 0) return null
            val factsPart = entryLine.substring(0, sp)
            val rawName = entryLine.substring(sp + 1)
            if (rawName.isEmpty()) return null

            val facts = HashMap<String, String>()
            for (token in factsPart.split(';')) {
                if (token.isEmpty()) continue
                val eq = token.indexOf('=')
                if (eq > 0) {
                    val k = token.substring(0, eq).trim().lowercase(Locale.ROOT)
                    val v = token.substring(eq + 1).trim()
                    facts[k] = v
                }
            }

            val typeVal = facts["type"]?.lowercase(Locale.ROOT) ?: "file"
            if (typeVal == "cdir" || typeVal == "pdir") return null

            val cleanName = if (explicitPath != null) {
                explicitPath.displayName
            } else {
                val leaf = if (rawName.contains('/')) rawName.trimEnd('/').substringAfterLast('/') else rawName
                if (leaf.isEmpty() || leaf == "." || leaf == "..") return null
                leaf
            }

            val isDir = typeVal == "dir"
            val isSymlink = typeVal.startsWith("os.unix=slink") || typeVal == "slink"
            val size = if (isDir) 0L else (facts["size"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
            val mtime = facts["modify"]?.let(::parseMlsdTimestamp) ?: 0L
            val perm = facts["perm"]?.lowercase(Locale.ROOT)
            val canRead = perm == null || perm.any { it in "rel" }
            val canWrite = if (perm == null) {
                true
            } else if (isDir) {
                perm.any { it in "cmfdw" }
            } else {
                perm.any { it in "adfw" }
            }

            val nodePath = explicitPath ?: run {
                val base = parentDir ?: return null
                val joined = if (base.normalizedRemotePath == "/") {
                    "/$cleanName"
                } else {
                    "${base.normalizedRemotePath}/$cleanName"
                }
                VfsPath.Ftp(base.serverId, PathUtils.normalizeLocalPath(joined))
            }

            return VfsNode(
                path = nodePath,
                name = cleanName,
                isDirectory = isDir,
                size = size,
                lastModified = mtime,
                canRead = canRead,
                canWrite = canWrite,
                isSymlink = isSymlink,
                posixPermissions = facts["unix.mode"],
            )
        }

        internal fun parseListLine(parentDir: VfsPath.Ftp, rawLine: String): VfsNode? {
            val line = rawLine.trimEnd('\r', '\n')
            if (line.isBlank() || line.startsWith("total ")) return null

            val unixMatch = UNIX_LIST_REGEX.matchEntire(line)
            if (unixMatch != null) {
                val perms = unixMatch.groupValues[1]
                val rawSize = unixMatch.groupValues[2].toLongOrNull() ?: 0L
                val dateToken = unixMatch.groupValues[3]
                val nameAndTarget = unixMatch.groupValues[4]

                val isSymlink = perms.startsWith('l')
                val isDir = perms.startsWith('d')
                val name: String
                val symlinkTarget: String?
                if (isSymlink && nameAndTarget.contains(" -> ")) {
                    name = nameAndTarget.substringBefore(" -> ")
                    symlinkTarget = nameAndTarget.substringAfter(" -> ")
                } else {
                    name = nameAndTarget
                    symlinkTarget = null
                }
                if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) return null

                val mtime = parseUnixDateToken(dateToken)
                val canRead = perms.length >= 2 && perms[1] == 'r'
                val canWrite = perms.length >= 3 && perms[2] == 'w'
                val joined = if (parentDir.normalizedRemotePath == "/") {
                    "/$name"
                } else {
                    "${parentDir.normalizedRemotePath}/$name"
                }
                return VfsNode(
                    path = VfsPath.Ftp(parentDir.serverId, PathUtils.normalizeLocalPath(joined)),
                    name = name,
                    isDirectory = isDir,
                    size = if (isDir) 0L else rawSize,
                    lastModified = mtime,
                    canRead = canRead,
                    canWrite = canWrite,
                    isSymlink = isSymlink,
                    posixPermissions = perms,
                    symlinkTarget = symlinkTarget,
                )
            }

            val dosMatch = DOS_LIST_REGEX.matchEntire(line.trimStart())
            if (dosMatch != null) {
                val datePart = dosMatch.groupValues[1]
                val timePart = dosMatch.groupValues[2]
                val dirOrSize = dosMatch.groupValues[3]
                val name = dosMatch.groupValues[4].trim()
                if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) return null

                val isDir = dirOrSize.equals("<DIR>", ignoreCase = true)
                val size = if (isDir) 0L else (dirOrSize.toLongOrNull() ?: 0L)
                val mtime = parseDosDateTime(datePart, timePart)
                val joined = if (parentDir.normalizedRemotePath == "/") {
                    "/$name"
                } else {
                    "${parentDir.normalizedRemotePath}/$name"
                }
                return VfsNode(
                    path = VfsPath.Ftp(parentDir.serverId, PathUtils.normalizeLocalPath(joined)),
                    name = name,
                    isDirectory = isDir,
                    size = size,
                    lastModified = mtime,
                )
            }
            return null
        }

        private fun parseUnixDateToken(token: String): Long {
            return runCatching {
                val parts = token.trim().split(Regex("""\s+"""))
                if (parts.size != 3) return 0L
                val month = parseShortMonth(parts[0]) ?: return 0L
                val day = parts[1].toIntOrNull() ?: return 0L
                val yearOrTime = parts[2]
                if (yearOrTime.contains(':')) {
                    val hm = yearOrTime.split(':')
                    val hour = hm.getOrNull(0)?.toIntOrNull() ?: 0
                    val minute = hm.getOrNull(1)?.toIntOrNull() ?: 0
                    val nowUtc = LocalDateTime.now(ZoneOffset.UTC)
                    var dt = LocalDateTime.of(nowUtc.year, month, day, hour, minute)
                    if (dt.isAfter(nowUtc.plusDays(2))) {
                        dt = dt.minusYears(1)
                    }
                    dt.toInstant(ZoneOffset.UTC).toEpochMilli()
                } else {
                    val year = yearOrTime.toIntOrNull() ?: return 0L
                    LocalDateTime.of(LocalDate.of(year, month, day), LocalTime.MIDNIGHT)
                        .toInstant(ZoneOffset.UTC)
                        .toEpochMilli()
                }
            }.getOrDefault(0L)
        }

        private fun parseDosDateTime(datePart: String, timePart: String): Long {
            return runCatching {
                val d = datePart.split('-')
                if (d.size != 3) return 0L
                val month = d[0].toInt()
                val day = d[1].toInt()
                val rawYear = d[2].toInt()
                val year = when {
                    rawYear >= 100 -> rawYear
                    rawYear >= 70 -> 1900 + rawYear
                    else -> 2000 + rawYear
                }
                val upperTime = timePart.uppercase(Locale.ROOT)
                val isPm = upperTime.endsWith("PM")
                val isAm = upperTime.endsWith("AM")
                val cleanHm = upperTime.removeSuffix("AM").removeSuffix("PM")
                val hm = cleanHm.split(':')
                var hour = hm.getOrNull(0)?.toIntOrNull() ?: 0
                val minute = hm.getOrNull(1)?.toIntOrNull() ?: 0
                if (isPm && hour < 12) hour += 12
                if (isAm && hour == 12) hour = 0
                LocalDateTime.of(year, month, day, hour, minute)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }.getOrDefault(0L)
        }

        private fun parseShortMonth(abbr: String): Month? = when (abbr.lowercase(Locale.ROOT)) {
            "jan" -> Month.JANUARY
            "feb" -> Month.FEBRUARY
            "mar" -> Month.MARCH
            "apr" -> Month.APRIL
            "may" -> Month.MAY
            "jun" -> Month.JUNE
            "jul" -> Month.JULY
            "aug" -> Month.AUGUST
            "sep" -> Month.SEPTEMBER
            "oct" -> Month.OCTOBER
            "nov" -> Month.NOVEMBER
            "dec" -> Month.DECEMBER
            else -> null
        }
    }
}
