package com.minifile.explorer.core.ftp

import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.BindException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal class FtpSession(
    private val controlSocket: Socket,
    private val config: FtpConfig,
    private val fs: FileSystem,
    private val emitEvent: (FtpEvent) -> Unit,
    private val onSessionClosed: (FtpSession) -> Unit,
) : Runnable, Closeable {

    private val closed = AtomicBoolean(false)
    private val resolver = FtpPathResolver(config.root, fs)
    private val clientIp: String = normalizeHostIp(controlSocket.inetAddress)

    @Volatile
    private var passiveServer: ServerSocket? = null

    @Volatile
    private var activeTarget: InetSocketAddress? = null

    @Volatile
    private var activeDataSocket: Socket? = null

    private var pendingUser: String? = null
    private var authenticated: Boolean = false
    private var cwd: String = "/"
    private var restOffset: Long = 0L
    private var renameFrom: ResolvedFtpPath? = null

    override fun run() {
        emitEvent(FtpEvent.ClientConnected(clientIp))
        try {
            controlSocket.soTimeout = config.idleTimeoutMs.coerceIn(100L, Int.MAX_VALUE.toLong()).toInt()
            controlSocket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(controlSocket.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(BufferedOutputStream(controlSocket.getOutputStream()), Charsets.UTF_8)

            fun reply(code: Int, text: String) {
                writer.write("$code $text\r\n")
                writer.flush()
            }

            fun replyMultiline(code: Int, header: String, lines: List<String>, footer: String) {
                writer.write("$code-$header\r\n")
                for (line in lines) {
                    writer.write(" $line\r\n")
                }
                writer.write("$code $footer\r\n")
                writer.flush()
            }

            reply(220, "${config.serverName} Server ready")

            while (!closed.get() && !controlSocket.isClosed) {
                val line = try {
                    reader.readLine()
                } catch (_: SocketTimeoutException) {
                    runCatching { reply(421, "Idle timeout, closing control connection.") }
                    break
                } ?: break

                if (line.isBlank()) continue
                val spaceIdx = line.indexOf(' ')
                val verb = (if (spaceIdx < 0) line else line.substring(0, spaceIdx)).uppercase(Locale.ROOT)
                val arg = if (spaceIdx < 0) "" else line.substring(spaceIdx + 1)

                val loggedArg = if (verb == "PASS") "****" else arg
                emitEvent(FtpEvent.Command(clientIp, verb, loggedArg))

                val shouldQuit = runBlocking {
                    handleCommand(verb, arg, ::reply, ::replyMultiline)
                }
                if (shouldQuit) break
            }
        } catch (e: IOException) {
            if (!closed.get()) {
                emitEvent(FtpEvent.Error("$clientIp: ${e.message ?: "I/O error"}"))
            }
        } catch (t: Throwable) {
            if (!closed.get()) {
                emitEvent(FtpEvent.Error("$clientIp: ${t.message ?: t::class.java.simpleName}"))
            }
        } finally {
            close()
            emitEvent(FtpEvent.ClientDisconnected(clientIp))
            onSessionClosed(this)
        }
    }

    private suspend fun handleCommand(
        verb: String,
        arg: String,
        reply: (Int, String) -> Unit,
        replyMultiline: (Int, String, List<String>, String) -> Unit,
    ): Boolean {
        when (verb) {
            "QUIT" -> {
                reply(221, "Goodbye")
                return true
            }

            "NOOP" -> {
                reply(200, "NOOP ok")
                return false
            }

            "SYST" -> {
                reply(215, "UNIX Type: L8")
                return false
            }

            "FEAT" -> {
                replyMultiline(
                    211,
                    "Features:",
                    listOf(
                        "UTF8",
                        "MLST type*;size*;modify*;perm*;",
                        "MLSD",
                        "SIZE",
                        "MDTM",
                        "REST STREAM",
                        "EPSV",
                        "PASV",
                        "TVFS",
                    ),
                    "End",
                )
                return false
            }

            "OPTS" -> {
                val upper = arg.trim().uppercase(Locale.ROOT)
                if (upper == "UTF8" || upper == "UTF8 ON") {
                    reply(200, "UTF8 mode enabled")
                } else {
                    reply(501, "Unsupported option")
                }
                return false
            }

            "HELP" -> {
                reply(214, "MiniFile RFC 959 / RFC 3659 FTP Server")
                return false
            }

            "USER" -> {
                if (arg.isBlank()) {
                    reply(501, "Syntax error: username required")
                    return false
                }
                pendingUser = arg
                authenticated = false
                when (config.auth) {
                    FtpAuth.Anonymous -> reply(331, "Anonymous login ok, send password")
                    is FtpAuth.Credentials -> reply(331, "Password required for $arg")
                }
                return false
            }

            "PASS" -> {
                val user = pendingUser
                if (user == null) {
                    reply(503, "Login with USER first")
                    return false
                }
                when (val auth = config.auth) {
                    FtpAuth.Anonymous -> {
                        authenticated = true
                        reply(230, "Anonymous user logged in")
                    }

                    is FtpAuth.Credentials -> {
                        val userMatch = constantTimeEquals(user, auth.username)
                        val passMatch = constantTimeEquals(arg, auth.password)
                        if (userMatch && passMatch) {
                            authenticated = true
                            reply(230, "User logged in, proceed")
                        } else {
                            authenticated = false
                            pendingUser = null
                            reply(530, "Authentication failed")
                        }
                    }
                }
                return false
            }
        }

        if (!authenticated) {
            reply(530, "Please login with USER and PASS")
            return false
        }

        if (verb != "RNTO") {
            renameFrom = null
        }

        try {
            when (verb) {
                "PWD", "XPWD" -> {
                    reply(257, "${FtpListingFormatter.quoteFtpPath(cwd)} is current directory")
                }

                "CWD", "XCWD" -> {
                    if (arg.isBlank()) {
                        reply(501, "Missing directory path")
                        return false
                    }
                    val target = resolver.resolve(cwd, arg)
                    val stat = fs.stat(target.vfsPath)
                    if (stat == null || !stat.isDirectory) {
                        reply(550, "Directory not found")
                    } else {
                        cwd = target.virtualPath
                        reply(250, "Directory changed to $cwd")
                    }
                }

                "CDUP", "XCUP" -> {
                    val target = resolver.resolve(cwd, "..")
                    cwd = target.virtualPath
                    reply(250, "Directory changed to $cwd")
                }

                "TYPE" -> {
                    val t = arg.trim().uppercase(Locale.ROOT)
                    if (t.startsWith("I") || t.startsWith("A") || t == "L 8") {
                        reply(200, "Type set to $t")
                    } else {
                        reply(504, "Unsupported TYPE")
                    }
                }

                "MODE" -> {
                    if (arg.trim().equals("S", ignoreCase = true)) {
                        reply(200, "Mode S ok")
                    } else {
                        reply(504, "Unsupported MODE")
                    }
                }

                "STRU" -> {
                    if (arg.trim().equals("F", ignoreCase = true)) {
                        reply(200, "Structure F ok")
                    } else {
                        reply(504, "Unsupported STRU")
                    }
                }

                "PASV" -> handlePasv(reply)
                "EPSV" -> handleEpsv(arg, reply)
                "PORT" -> handlePort(arg, reply)
                "EPRT" -> handleEprt(arg, reply)

                "STAT" -> {
                    if (arg.isBlank()) {
                        reply(211, "${config.serverName} status: connected from $clientIp, cwd=$cwd")
                    } else {
                        val target = resolver.resolve(cwd, arg)
                        val node = fs.stat(target.vfsPath)
                        if (node == null) {
                            reply(550, "Path not found")
                        } else if (node.isDirectory) {
                            val children = fs.list(target.vfsPath, includeHidden = true)
                            replyMultiline(
                                213,
                                "Status of ${target.virtualPath}:",
                                children.map { FtpListingFormatter.formatUnixListLine(it) },
                                "End of status",
                            )
                        } else {
                            replyMultiline(
                                213,
                                "Status of ${target.virtualPath}:",
                                listOf(FtpListingFormatter.formatUnixListLine(node)),
                                "End of status",
                            )
                        }
                    }
                }

                "ABOR" -> {
                    closeDataChannels()
                    reply(226, "ABOR command successful")
                }

                "SIZE" -> {
                    val target = resolver.resolve(cwd, arg)
                    val node = fs.stat(target.vfsPath)
                    if (node == null || node.isDirectory || node.size < 0L) {
                        reply(550, "Could not get file size")
                    } else {
                        reply(213, node.size.toString())
                    }
                }

                "MDTM" -> {
                    val target = resolver.resolve(cwd, arg)
                    val node = fs.stat(target.vfsPath)
                    if (node == null || node.isDirectory || node.lastModified <= 0L) {
                        reply(550, "Could not get modification time")
                    } else {
                        reply(213, FtpListingFormatter.formatMdtmTimestamp(node.lastModified))
                    }
                }

                "MLST" -> {
                    val target = if (arg.isBlank()) resolver.resolve(cwd) else resolver.resolve(cwd, arg)
                    val node = fs.stat(target.vfsPath)
                    if (node == null) {
                        reply(550, "Path not found")
                    } else {
                        val factLine = FtpListingFormatter.formatMlsdFacts(
                            node = node,
                            serverReadOnly = config.readOnly,
                            displayName = target.virtualPath,
                        )
                        replyMultiline(250, "Listing ${target.virtualPath}", listOf(factLine), "End")
                    }
                }

                "MLSD" -> {
                    val target = if (arg.isBlank()) resolver.resolve(cwd) else resolver.resolve(cwd, arg)
                    val node = fs.stat(target.vfsPath)
                    if (node == null || !node.isDirectory) {
                        reply(550, "Not a directory")
                    } else {
                        val children = fs.list(target.vfsPath, includeHidden = true)
                        withDataConnection(reply, "150 Opening UTF-8 data connection for MLSD", writeToPeer = { out ->
                            val w = OutputStreamWriter(out, Charsets.UTF_8)
                            for (child in children) {
                                w.write(FtpListingFormatter.formatMlsdFacts(child, config.readOnly))
                                w.write("\r\n")
                            }
                            w.flush()
                        })
                    }
                }

                "LIST" -> {
                    val pathArg = stripOptionalListFlags(arg)
                    val target = if (pathArg.isEmpty()) resolver.resolve(cwd) else resolver.resolve(cwd, pathArg)
                    val node = fs.stat(target.vfsPath)
                    if (node == null) {
                        reply(550, "Path not found")
                    } else {
                        val entries = if (node.isDirectory) {
                            fs.list(target.vfsPath, includeHidden = true)
                        } else {
                            listOf(node)
                        }
                        val now = System.currentTimeMillis()
                        withDataConnection(reply, "150 Opening ASCII mode data connection for file list", writeToPeer = { out ->
                            val w = OutputStreamWriter(out, Charsets.UTF_8)
                            for (entry in entries) {
                                w.write(FtpListingFormatter.formatUnixListLine(entry, now))
                                w.write("\r\n")
                            }
                            w.flush()
                        })
                    }
                }

                "NLST" -> {
                    val pathArg = stripOptionalListFlags(arg)
                    val target = if (pathArg.isEmpty()) resolver.resolve(cwd) else resolver.resolve(cwd, pathArg)
                    val node = fs.stat(target.vfsPath)
                    if (node == null) {
                        reply(550, "Path not found")
                    } else {
                        val names = if (node.isDirectory) {
                            fs.list(target.vfsPath, includeHidden = true).map { it.name }
                        } else {
                            listOf(node.name)
                        }
                        withDataConnection(reply, "150 Opening data connection for name list", writeToPeer = { out ->
                            val w = OutputStreamWriter(out, Charsets.UTF_8)
                            for (n in names) {
                                w.write(n)
                                w.write("\r\n")
                            }
                            w.flush()
                        })
                    }
                }

                "REST" -> {
                    val offset = arg.trim().toLongOrNull()
                    if (offset == null || offset < 0L) {
                        reply(501, "Invalid REST offset")
                    } else {
                        restOffset = offset
                        reply(350, "Restarting at $offset. Send STORE or RETRIEVE to initiate transfer")
                    }
                }

                "RETR" -> {
                    val offset = restOffset
                    restOffset = 0L
                    if (arg.isBlank()) {
                        reply(501, "Missing file path")
                        return false
                    }
                    val target = resolver.resolve(cwd, arg)
                    val node = fs.stat(target.vfsPath)
                    if (node == null || node.isDirectory) {
                        reply(550, "File not found")
                        return false
                    }
                    val startMs = System.currentTimeMillis()
                    var transferred = 0L
                    fs.openInput(target.vfsPath, offset).use { input ->
                        withDataConnection(reply, "150 Opening BINARY mode data connection for ${node.name}", writeToPeer = { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                if (n > 0) {
                                    out.write(buf, 0, n)
                                    transferred += n
                                }
                            }
                            out.flush()
                        })
                    }
                    emitEvent(
                        FtpEvent.Transfer(
                            clientIp = clientIp,
                            direction = TransferDirection.DOWNLOAD,
                            path = target.virtualPath,
                            bytes = transferred,
                            durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                        ),
                    )
                }

                "STOR", "APPE" -> {
                    val offset = restOffset
                    restOffset = 0L
                    if (config.readOnly) {
                        reply(550, "Read-only server")
                        return false
                    }
                    if (arg.isBlank()) {
                        reply(501, "Missing file path")
                        return false
                    }
                    val resolved = resolver.resolveParentAndName(cwd, arg)
                    if (verb == "STOR" && offset > 0L && resolved.parentVfsPath is VfsPath.Saf) {
                        reply(504, "REST not supported for STOR on this volume")
                        return false
                    }

                    val existingCandidate = fs.child(resolved.parentVfsPath, resolved.name)
                    resolver.ensureLocalInside(existingCandidate)
                    val existingStat = fs.stat(existingCandidate)
                    if (existingStat != null && existingStat.isDirectory) {
                        reply(550, "Cannot overwrite a directory")
                        return false
                    }

                    val targetFile = existingStat?.path ?: fs.createFile(resolved.parentVfsPath, resolved.name)
                    resolver.ensureLocalInside(targetFile)

                    val startMs = System.currentTimeMillis()
                    var transferred = 0L
                    val outStream = when {
                        verb == "APPE" -> fs.openOutput(targetFile, append = true)
                        offset > 0L && targetFile is VfsPath.Local -> LocalFileSystem().openOutputAtOffset(targetFile, offset)
                        else -> fs.openOutput(targetFile, append = false)
                    }

                    outStream.use { fileOut ->
                        withDataConnection(reply, "150 Opening BINARY mode data connection for ${resolved.name}", readFromPeer = { peerIn ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = peerIn.read(buf)
                                if (n < 0) break
                                if (n > 0) {
                                    fileOut.write(buf, 0, n)
                                    transferred += n
                                }
                            }
                            fileOut.flush()
                        })
                    }

                    emitEvent(
                        FtpEvent.Transfer(
                            clientIp = clientIp,
                            direction = TransferDirection.UPLOAD,
                            path = resolved.virtualPath,
                            bytes = transferred,
                            durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                        ),
                    )
                }

                "DELE" -> {
                    if (config.readOnly) {
                        reply(550, "Read-only server")
                        return false
                    }
                    val target = resolver.resolve(cwd, arg)
                    val node = fs.stat(target.vfsPath)
                    if (node == null || node.isDirectory) {
                        reply(550, "File not found")
                    } else if (fs.delete(target.vfsPath)) {
                        reply(250, "File deleted")
                    } else {
                        reply(550, "Could not delete file")
                    }
                }

                "MKD", "XMKD" -> {
                    if (config.readOnly) {
                        reply(550, "Read-only server")
                        return false
                    }
                    val resolved = resolver.resolveParentAndName(cwd, arg)
                    val candidate = fs.child(resolved.parentVfsPath, resolved.name)
                    if (fs.exists(candidate)) {
                        reply(550, "Directory or file already exists")
                    } else {
                        val created = fs.createDirectory(resolved.parentVfsPath, resolved.name)
                        resolver.ensureLocalInside(created)
                        reply(257, "${FtpListingFormatter.quoteFtpPath(resolved.virtualPath)} directory created")
                    }
                }

                "RMD", "XRMD" -> {
                    if (config.readOnly) {
                        reply(550, "Read-only server")
                        return false
                    }
                    val target = resolver.resolve(cwd, arg)
                    if (target.virtualPath == "/") {
                        reply(550, "Cannot remove root directory")
                        return false
                    }
                    val node = fs.stat(target.vfsPath)
                    if (node == null || !node.isDirectory) {
                        reply(550, "Directory not found")
                    } else if (fs.delete(target.vfsPath)) {
                        reply(250, "Directory removed")
                    } else {
                        reply(550, "Directory not empty or could not be removed")
                    }
                }

                "RNFR" -> {
                    if (config.readOnly) {
                        reply(550, "Read-only server")
                        return false
                    }
                    val target = resolver.resolve(cwd, arg)
                    if (target.virtualPath == "/" || !fs.exists(target.vfsPath)) {
                        reply(550, "Source path not found")
                    } else {
                        renameFrom = target
                        reply(350, "File or directory exists, ready for destination name")
                    }
                }

                "RNTO" -> {
                    if (config.readOnly) {
                        renameFrom = null
                        reply(550, "Read-only server")
                        return false
                    }
                    val src = renameFrom
                    renameFrom = null
                    if (src == null) {
                        reply(503, "Bad sequence of commands: RNFR required before RNTO")
                        return false
                    }
                    val dst = resolver.resolveParentAndName(cwd, arg)
                    if (src.vfsPath::class != dst.parentVfsPath::class) {
                        reply(550, "Cross-backend rename not supported")
                        return false
                    }
                    val srcParent = fs.parent(src.vfsPath)
                    if (srcParent == dst.parentVfsPath) {
                        val renamed = fs.rename(src.vfsPath, dst.name)
                        resolver.ensureLocalInside(renamed)
                        reply(250, "Rename successful")
                    } else if (src.vfsPath is VfsPath.Local && dst.parentVfsPath is VfsPath.Local) {
                        val srcFile = File(src.vfsPath.normalized)
                        val dstFile = File(dst.parentVfsPath.normalized, dst.name)
                        resolver.ensureLocalInside(VfsPath.Local(dstFile.path))
                        Files.move(srcFile.toPath(), dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        reply(250, "Rename successful")
                    } else {
                        val moved = fs.moveWithinSameBackend(src.vfsPath, dst.parentVfsPath)
                            ?: run {
                                reply(550, "Move across folders failed on this volume")
                                return false
                            }
                        if (moved.displayName != dst.name) {
                            fs.rename(moved, dst.name)
                        }
                        reply(250, "Rename successful")
                    }
                }

                else -> reply(502, "Command '$verb' not implemented")
            }
        } catch (e: FtpAccessDeniedException) {
            reply(550, e.message ?: "Access denied")
        } catch (e: IOException) {
            reply(550, e.message ?: "File operation failed")
        } catch (e: IllegalArgumentException) {
            reply(550, e.message ?: "Invalid argument")
        }
        return false
    }

    private suspend fun stripOptionalListFlags(rawArg: String): String {
        val trimmed = rawArg.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("-")) return trimmed
        // If a file/folder literally named "-foo" exists, do not strip it!
        val literalExists = runCatching { fs.exists(resolver.resolve(cwd, trimmed).vfsPath) }.getOrDefault(false)
        if (literalExists) return trimmed
        val space = trimmed.indexOf(' ')
        return if (space < 0) "" else trimmed.substring(space + 1).trim()
    }

    private class PassivePortExhaustedException : Exception("No free port in configured passive range")

    private fun handlePasv(reply: (Int, String) -> Unit) {
        val localAddr = controlSocket.localAddress
        val ipv4Bytes = extractIpv4Bytes(localAddr)
        if (ipv4Bytes == null) {
            // IPv6 control connection cannot be encoded in RFC 959 PASV; RFC 2428 §3 directs 522 -> EPSV
            reply(522, "Network protocol not supported, use (1)")
            return
        }
        closeDataChannels()
        val server = try {
            bindPassiveServerSocket()
        } catch (_: PassivePortExhaustedException) {
            reply(425, "Cannot open passive connection")
            return
        }
        passiveServer = server
        val port = server.localPort
        val h1 = ipv4Bytes[0].toInt() and 0xFF
        val h2 = ipv4Bytes[1].toInt() and 0xFF
        val h3 = ipv4Bytes[2].toInt() and 0xFF
        val h4 = ipv4Bytes[3].toInt() and 0xFF
        val p1 = (port ushr 8) and 0xFF
        val p2 = port and 0xFF
        reply(227, "Entering Passive Mode ($h1,$h2,$h3,$h4,$p1,$p2)")
    }

    private fun handleEpsv(arg: String, reply: (Int, String) -> Unit) {
        val trimmed = arg.trim().uppercase(Locale.ROOT)
        if (trimmed == "ALL") {
            reply(200, "EPSV ALL ok")
            return
        }
        if (trimmed.isNotEmpty() && trimmed != "1" && trimmed != "2") {
            reply(522, "Network protocol not supported, use (1,2)")
            return
        }
        closeDataChannels()
        val server = try {
            bindPassiveServerSocket()
        } catch (_: PassivePortExhaustedException) {
            reply(425, "Cannot open passive connection")
            return
        }
        passiveServer = server
        reply(229, "Entering Extended Passive Mode (|||${server.localPort}|)")
    }

    private fun handlePort(arg: String, reply: (Int, String) -> Unit) {
        val parts = arg.trim().split(',')
        if (parts.size != 6) {
            reply(501, "Invalid PORT syntax")
            return
        }
        val nums = parts.map { it.trim().toIntOrNull() }
        if (nums.any { it == null || it !in 0..255 }) {
            reply(501, "Invalid PORT numbers")
            return
        }
        val host = "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}"
        val port = (nums[4]!! shl 8) or nums[5]!!
        if (port <= 0) {
            reply(501, "Invalid PORT")
            return
        }
        val addr = InetAddress.getByName(host)
        if (!FtpServerEngine.isAllowedClientAddress(addr) ||
            (!config.allowForeignDataPeer && normalizeHostIp(addr) != clientIp)
        ) {
            reply(504, "Foreign PORT address rejected")
            return
        }
        closeDataChannels()
        activeTarget = InetSocketAddress(addr, port)
        reply(200, "PORT command successful")
    }

    private fun handleEprt(arg: String, reply: (Int, String) -> Unit) {
        val trimmed = arg.trim()
        if (trimmed.length < 7) {
            reply(501, "Invalid EPRT syntax")
            return
        }
        val delim = trimmed[0]
        val fields = trimmed.split(delim)
        if (fields.size < 5) {
            reply(501, "Invalid EPRT syntax")
            return
        }
        val af = fields[1]
        val host = fields[2]
        val port = fields[3].toIntOrNull()
        if (af != "1" && af != "2") {
            reply(522, "Network protocol not supported, use (1,2)")
            return
        }
        if (port == null || port !in 1..65535) {
            reply(501, "Invalid EPRT port")
            return
        }
        val addr = InetAddress.getByName(host)
        if (!FtpServerEngine.isAllowedClientAddress(addr) ||
            (!config.allowForeignDataPeer && normalizeHostIp(addr) != clientIp)
        ) {
            reply(504, "Foreign EPRT address rejected")
            return
        }
        closeDataChannels()
        activeTarget = InetSocketAddress(addr, port)
        reply(200, "EPRT command successful")
    }

    private fun bindPassiveServerSocket(): ServerSocket {
        for (port in config.passivePorts) {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                ss.soTimeout = 30_000
                return ss
            } catch (_: BindException) {
                runCatching { ss?.close() }
                continue
            } catch (_: IOException) {
                runCatching { ss?.close() }
                continue
            }
        }
        throw PassivePortExhaustedException()
    }

    private fun withDataConnection(
        reply: (Int, String) -> Unit,
        openingBanner: String,
        writeToPeer: ((java.io.OutputStream) -> Unit)? = null,
        readFromPeer: ((java.io.InputStream) -> Unit)? = null,
    ) {
        val pasv = passiveServer
        val active = activeTarget
        if (pasv == null && active == null) {
            reply(425, "Use PASV, EPSV, or PORT first")
            return
        }

        var dataSock: Socket? = null
        try {
            if (pasv != null) {
                reply(150, openingBanner.removePrefix("150 "))
                val accepted = pasv.accept()
                runCatching { pasv.close() }
                passiveServer = null
                val peerAddr = accepted.inetAddress
                val peerIp = normalizeHostIp(peerAddr)
                if (peerAddr == null ||
                    !FtpServerEngine.isAllowedClientAddress(peerAddr) ||
                    (!config.allowForeignDataPeer && peerIp != clientIp)
                ) {
                    runCatching { accepted.close() }
                    reply(425, "Data connection peer mismatch")
                    return
                }
                accepted.soTimeout = 30_000
                dataSock = accepted
            } else if (active != null) {
                activeTarget = null
                val sock = Socket()
                sock.connect(active, 15_000)
                sock.soTimeout = 30_000
                dataSock = sock
                reply(150, openingBanner.removePrefix("150 "))
            }

            activeDataSocket = dataSock
            val sock = dataSock!!
            if (writeToPeer != null) {
                val buffered = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
                writeToPeer(buffered)
                buffered.flush()
            }
            if (readFromPeer != null) {
                readFromPeer(sock.getInputStream())
            }
            runCatching { sock.close() }
            activeDataSocket = null
            reply(226, "Transfer complete")
        } catch (e: SocketTimeoutException) {
            reply(425, "Data connection timed out")
        } catch (e: IOException) {
            reply(426, "Connection closed; transfer aborted: ${e.message ?: "I/O error"}")
        } finally {
            activeDataSocket = null
            runCatching { dataSock?.close() }
            closeDataChannels()
        }
    }

    private fun closeDataChannels() {
        passiveServer?.let { runCatching { it.close() } }
        passiveServer = null
        activeTarget = null
        activeDataSocket?.let { runCatching { it.close() } }
        activeDataSocket = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeDataChannels()
        runCatching { controlSocket.close() }
    }

    companion object {
        internal fun extractIpv4Bytes(address: InetAddress?): ByteArray? {
            if (address == null) return null
            if (address is Inet4Address) return address.address
            if (address is Inet6Address) {
                val raw = address.address
                // Check IPv4-mapped IPv6 ::ffff:a.b.c.d
                val isV4Mapped = raw.size == 16 &&
                    (0..9).all { raw[it] == 0.toByte() } &&
                    raw[10] == 0xFF.toByte() &&
                    raw[11] == 0xFF.toByte()
                if (isV4Mapped) {
                    return raw.copyOfRange(12, 16)
                }
            }
            return null
        }

        internal fun normalizeHostIp(address: InetAddress?): String {
            if (address == null) return "unknown"
            val v4 = extractIpv4Bytes(address)
            if (v4 != null) {
                return "${v4[0].toInt() and 0xFF}.${v4[1].toInt() and 0xFF}.${v4[2].toInt() and 0xFF}.${v4[3].toInt() and 0xFF}"
            }
            return address.hostAddress?.substringBefore('%') ?: "unknown"
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
