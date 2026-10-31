package com.minifile.explorer.core.ftp

import com.minifile.explorer.core.vfs.FileSystem
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FtpServerEngine(
    val config: FtpConfig,
    private val fs: FileSystem,
    val externalIpProvider: () -> List<String> = { emptyList() },
) {
    private val _events = MutableSharedFlow<FtpEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<FtpEvent> = _events.asSharedFlow()

    private val _activeSessions = MutableStateFlow(0)
    val activeSessions: StateFlow<Int> = _activeSessions.asStateFlow()

    private val running = AtomicBoolean(false)
    private val sessions = Collections.synchronizedSet(HashSet<FtpSession>())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    var boundPort: Int = -1
        private set

    val isRunning: Boolean
        get() = running.get()

    @Synchronized
    fun start() {
        if (running.get()) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        val bindSockAddr = if (config.bindAddress == "0.0.0.0" || config.bindAddress == "::" || config.bindAddress.isBlank()) {
            InetSocketAddress(config.port)
        } else {
            InetSocketAddress(InetAddress.getByName(config.bindAddress), config.port)
        }
        ss.bind(bindSockAddr)
        boundPort = ss.localPort
        serverSocket = ss
        running.set(true)

        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "minifile-ftp-worker").apply { isDaemon = true }
        }
        executor = pool
        _events.tryEmit(FtpEvent.Started(boundPort))

        pool.execute {
            acceptLoop(ss, pool)
        }
    }

    private fun acceptLoop(ss: ServerSocket, pool: ExecutorService) {
        while (running.get() && !ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                if (running.get()) {
                    _events.tryEmit(FtpEvent.Error("Accept loop terminated"))
                }
                break
            }

            val remoteAddr = client.inetAddress
            val localAddr = client.localAddress
            if (remoteAddr == null || localAddr == null ||
                !isAllowedClientAddress(remoteAddr) || !isAllowedClientAddress(localAddr)
            ) {
                runCatching { client.close() }
                continue
            }

            if (sessions.size >= config.maxClients) {
                runCatching {
                    val w = OutputStreamWriter(BufferedOutputStream(client.getOutputStream()), Charsets.UTF_8)
                    w.write("421 Too many connected users (max ${config.maxClients})\r\n")
                    w.flush()
                    client.close()
                }
                continue
            }

            val session = FtpSession(
                controlSocket = client,
                config = config,
                fs = fs,
                emitEvent = { ev -> _events.tryEmit(ev) },
                onSessionClosed = { closedSession ->
                    sessions.remove(closedSession)
                    _activeSessions.value = sessions.size
                },
            )
            sessions.add(session)
            _activeSessions.value = sessions.size
            pool.execute(session)
        }
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null

        val snapshot = synchronized(sessions) { sessions.toList() }
        for (s in snapshot) {
            runCatching { s.close() }
        }
        sessions.clear()
        _activeSessions.value = 0

        executor?.shutdownNow()
        executor = null
        boundPort = -1
        _events.tryEmit(FtpEvent.Stopped)
    }

    companion object {
        /**
         * Strict LAN / loopback firewall check: allows ONLY loopback (`127.0.0.0/8`, `::1`),
         * RFC 1918 site-local (`10/8`, `172.16/12`, `192.168/16`, `fec0::/10`), link-local
         * (`169.254/16`, `fe80::/10`), IPv4 CGNAT (`100.64.0.0/10`), or IPv6 ULA (`fc00::/7`).
         * Rejects cellular/public IPv4 & global IPv6 WAN addresses, multicast, and unspecified `0.0.0.0`/`::`.
         */
        fun isAllowedClientAddress(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress || addr.isMulticastAddress) return false

            val v4 = FtpSession.extractIpv4Bytes(addr)
            if (v4 != null) {
                val b0 = v4[0].toInt() and 0xFF
                val b1 = v4[1].toInt() and 0xFF
                return when {
                    b0 == 127 -> true // 127.0.0.0/8 loopback
                    b0 == 10 -> true // 10.0.0.0/8 private
                    b0 == 172 && b1 in 16..31 -> true // 172.16.0.0/12 private
                    b0 == 192 && b1 == 168 -> true // 192.168.0.0/16 private
                    b0 == 169 && b1 == 254 -> true // 169.254.0.0/16 link-local
                    b0 == 100 && b1 in 64..127 -> true // 100.64.0.0/10 CGNAT
                    else -> false
                }
            }

            if (addr is Inet6Address) {
                if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) {
                    return true
                }
                val raw = addr.address
                if (raw.size == 16 && (raw[0].toInt() and 0xFE) == 0xFC) {
                    return true // fc00::/7 Unique Local Address (fc00:: .. fdff::)
                }
            }
            return false
        }
    }
}
