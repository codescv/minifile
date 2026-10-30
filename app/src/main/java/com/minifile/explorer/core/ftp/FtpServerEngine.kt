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
}
