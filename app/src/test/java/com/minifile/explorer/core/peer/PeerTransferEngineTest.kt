package com.minifile.explorer.core.peer

import com.minifile.explorer.core.http.HttpConfig
import com.minifile.explorer.core.http.HttpEvent
import com.minifile.explorer.core.http.HttpFileServerEngine
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class PeerTransferEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var senderDir: File
    private lateinit var receiverRootDir: File
    private val events = Collections.synchronizedList(ArrayList<HttpEvent>())
    private var server: HttpFileServerEngine? = null
    private var eventsJob: Job? = null

    @Before
    fun setUp() {
        senderDir = tempFolder.newFolder("sender_root")
        receiverRootDir = tempFolder.newFolder("receiver_root")
        events.clear()
    }

    @After
    fun tearDown() {
        eventsJob?.cancel()
        eventsJob = null
        server?.stop()
        server = null
    }

    private fun startReceiver(
        readOnly: Boolean = false,
        freeSpaceReserveBytes: Long = 0L,
        deviceName: String = "Pixel 7",
    ): Pair<HttpFileServerEngine, PeerInfo> {
        eventsJob?.cancel()
        val srv = HttpFileServerEngine(
            config = HttpConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(receiverRootDir.canonicalPath),
                readOnly = readOnly,
                deviceId = "receiver-device-id",
                deviceName = deviceName,
                appVersion = "1.0.6",
                peerInboxDir = "Received",
                freeSpaceReserveBytes = freeSpaceReserveBytes,
            ),
            fs = LocalFileSystem(),
        )
        eventsJob = CoroutineScope(Dispatchers.Unconfined).launch {
            srv.events.collect { events.add(it) }
        }
        srv.start()
        server = srv
        val peer = PeerInfo(
            id = "receiver-device-id",
            name = deviceName,
            host = "127.0.0.1",
            port = srv.boundPort,
            version = "1.0.6",
            readOnly = readOnly,
            source = PeerSource.BEACON,
            lastSeenMs = System.currentTimeMillis(),
        )
        return srv to peer
    }

    private fun assertNoPartFilesRemain(dir: File = receiverRootDir) {
        val leftovers = dir.walkTopDown().filter { it.name.contains(".part-") }.toList()
        assertTrue("Expected zero leftover .part temp files, found: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun roundTripNestedFoldersEmptyDirsAndMtime_writesIntoReceivedAndEmitsSessionEvents() = runBlocking {
        val fs = LocalFileSystem()
        val (_, peer) = startReceiver()

        // Build nested tree on sender: TripPhotos/Day1/img.bin, TripPhotos/EmptyAlbum/, standalone note.txt
        val tripDir = File(senderDir, "Trip Photos").apply { mkdirs() }
        val day1Dir = File(tripDir, "Day 1").apply { mkdirs() }
        val emptyDir = File(tripDir, "Empty Album").apply { mkdirs() }
        val imgBytes = ByteArray(600 * 1024) { idx -> ((idx * 17 + 3) and 0xFF).toByte() } // > 2 chunks (256 KB)
        val imgFile = File(day1Dir, "sunset.bin").apply { writeBytes(imgBytes) }
        val noteFile = File(senderDir, "notes+todo.txt").apply { writeText("Pack charger & passport\n") }

        val targetMtime = 1_715_000_000_000L
        assertTrue(imgFile.setLastModified(targetMtime))
        assertTrue(noteFile.setLastModified(targetMtime - 60_000L))
        assertTrue(emptyDir.setLastModified(targetMtime - 120_000L))

        val sendItems = PeerTransferEngine.collectSendItems(
            fs = fs,
            roots = listOf(
                VfsPath.Local(tripDir.canonicalPath),
                VfsPath.Local(noteFile.canonicalPath),
            ),
            includeHidden = false,
        )
        assertEquals(3, sendItems.size) // sunset.bin, Empty Album (dir), notes+todo.txt

        val progressSnapshots = ArrayList<OperationProgress>()
        val sink = object : ProgressSink {
            override fun onProgress(progress: OperationProgress) {
                progressSnapshots.add(progress)
            }
            override fun isCancelled(): Boolean = false
        }

        PeerHttpClient().use { client ->
            val res = PeerTransferEngine(
                client = client,
                senderDeviceNameProvider = { "Galaxy S24" },
            ).send(peer = peer, items = sendItems, sink = sink)

            assertFalse(res.cancelled)
            assertEquals(2, res.files)
            assertEquals(1, res.dirs)
            assertEquals(0, res.skipped)
            assertEquals(imgBytes.size.toLong() + noteFile.length(), res.bytes)
        }

        val inbox = File(receiverRootDir, "Received")
        val rxImg = File(inbox, "Trip Photos/Day 1/sunset.bin")
        val rxEmpty = File(inbox, "Trip Photos/Empty Album")
        val rxNote = File(inbox, "notes+todo.txt")

        assertTrue(rxImg.isFile)
        assertArrayEquals(imgBytes, rxImg.readBytes())
        assertTrue("Expected mtime preserved within 2s", abs(rxImg.lastModified() - targetMtime) <= 2_000L)

        assertTrue(rxEmpty.isDirectory)
        assertTrue(rxNote.isFile)
        assertEquals("Pack charger & passport\n", rxNote.readText())
        assertNoPartFilesRemain()

        assertTrue(progressSnapshots.isNotEmpty())
        assertEquals(progressSnapshots.last().totalBytes, progressSnapshots.last().processedBytes)

        synchronized(events) {
            assertTrue(events.any { it is HttpEvent.PeerSessionStart && it.deviceName == "Galaxy S24" })
            assertTrue(events.any { it is HttpEvent.PeerProgress })
            assertTrue(events.any { it is HttpEvent.PeerSessionEnd && it.ok && !it.cancelled && it.files == 2 })
        }
    }

    @Test
    fun conflictPolicies_renameOverwriteAndSkipBehaveDeterministically() = runBlocking {
        val (_, peer) = startReceiver()
        val inbox = File(receiverRootDir, "Received").apply { mkdirs() }
        File(inbox, "report.txt").writeText("original")

        fun singleItem(content: String) = listOf(
            PeerSendItem(
                relPath = "report.txt",
                size = content.toByteArray().size.toLong(),
                mtime = 1_715_000_000_000L,
                open = { ByteArrayInputStream(content.toByteArray()) },
            ),
        )

        PeerHttpClient().use { client ->
            val engine = PeerTransferEngine(client = client)

            // 1. RENAME -> creates "report (1).txt" without touching "report.txt"
            val r1 = engine.send(peer, singleItem("second copy"), conflict = PeerConflictPolicy.RENAME)
            assertEquals(1, r1.files)
            assertEquals("original", File(inbox, "report.txt").readText())
            assertEquals("second copy", File(inbox, "report (1).txt").readText())

            // 2. SKIP -> keeps existing "report.txt" and increments skipped counter
            val r2 = engine.send(peer, singleItem("ignored copy"), conflict = PeerConflictPolicy.SKIP)
            assertEquals(0, r2.files)
            assertEquals(1, r2.skipped)
            assertEquals("original", File(inbox, "report.txt").readText())

            // 3. OVERWRITE -> atomically replaces "report.txt" via .part rename
            val r3 = engine.send(peer, singleItem("overwritten content"), conflict = PeerConflictPolicy.OVERWRITE)
            assertEquals(1, r3.files)
            assertEquals("overwritten content", File(inbox, "report.txt").readText())
        }
        assertNoPartFilesRemain()
    }

    @Test
    fun preContinueGuards_rejectReadOnlyTraversalAndInsufficientSpaceBeforeStreamingBody() = runBlocking {
        // 1. Read-only receiver rejects on /api/peer/begin with 403 before opening item stream
        val (_, roPeer) = startReceiver(readOnly = true, deviceName = "Tablet RO")
        val openedStream = AtomicBoolean(false)
        val dummyItem = PeerSendItem(
            relPath = "big.bin",
            size = 1024 * 1024L,
            mtime = 1_715_000_000_000L,
            open = {
                openedStream.set(true)
                ByteArrayInputStream(ByteArray(1024 * 1024))
            },
        )
        PeerHttpClient().use { client ->
            try {
                PeerTransferEngine(client = client).send(roPeer, listOf(dummyItem))
                fail("Expected 403 read-only PeerTransferException")
            } catch (e: PeerTransferException) {
                assertEquals(403, e.httpStatus)
                assertEquals("Tablet RO is read-only (Transfer ▸ Settings)", e.userMessage)
            }
        }
        assertFalse("Body stream must never be opened when readOnly=true", openedStream.get())
        server?.stop()

        // 2. Traversal path rejected before 100 Continue (open() never invoked)
        val (_, rwPeer) = startReceiver(readOnly = false, deviceName = "Pixel 7")
        val evilOpened = AtomicBoolean(false)
        val evilItem = PeerSendItem(
            relPath = "../escape.txt",
            size = 64L,
            mtime = 1_715_000_000_000L,
            open = {
                evilOpened.set(true)
                ByteArrayInputStream(ByteArray(64))
            },
        )
        PeerHttpClient().use { client ->
            try {
                PeerTransferEngine(client = client).send(rwPeer, listOf(evilItem))
                fail("Expected 403 traversal PeerTransferException")
            } catch (e: PeerTransferException) {
                assertEquals(403, e.httpStatus)
            }
        }
        assertFalse("Traversal item body must be rejected before 100 Continue", evilOpened.get())
        assertFalse(File(receiverRootDir, "escape.txt").exists())
        server?.stop()

        // 3. Insufficient free space (huge reserve) rejected with 507 before 100 Continue
        val (_, fullPeer) = startReceiver(
            readOnly = false,
            freeSpaceReserveBytes = Long.MAX_VALUE / 2,
            deviceName = "LowSpacePhone",
        )
        val fullOpened = AtomicBoolean(false)
        val largeItem = PeerSendItem(
            relPath = "video.mp4",
            size = 4096L,
            mtime = 1_715_000_000_000L,
            open = {
                fullOpened.set(true)
                ByteArrayInputStream(ByteArray(4096))
            },
        )
        PeerHttpClient().use { client ->
            try {
                PeerTransferEngine(client = client).send(fullPeer, listOf(largeItem))
                fail("Expected 507 Insufficient Storage PeerTransferException")
            } catch (e: PeerTransferException) {
                assertEquals(507, e.httpStatus)
                assertEquals("Not enough space on LowSpacePhone", e.userMessage)
            }
        }
        assertFalse("507 must be returned before 100 Continue without streaming body", fullOpened.get())
        assertNoPartFilesRemain()
    }

    @Test
    fun senderCancelAndReceiverReject_abortCleanlyWithoutLeftoverPartFiles() = runBlocking {
        val (srv, peer) = startReceiver(deviceName = "Pixel 8")
        val twoMegs = ByteArray(2 * 1024 * 1024) { 0x5A }

        // 1. Sender cancels after first chunk
        val senderCancelled = AtomicBoolean(false)
        val cancelClient = PeerHttpClient()
        val cancelSink = object : ProgressSink {
            override fun onProgress(progress: OperationProgress) {
                if (progress.processedBytes >= PeerTransferEngine.CHUNK_BYTES) {
                    senderCancelled.set(true)
                    cancelClient.cancelActive()
                }
            }
            override fun isCancelled(): Boolean = senderCancelled.get()
        }

        cancelClient.use { client ->
            val res = PeerTransferEngine(client = client).send(
                peer = peer,
                items = listOf(
                    PeerSendItem(
                        relPath = "cancelled_sender.bin",
                        size = twoMegs.size.toLong(),
                        mtime = 1_715_000_000_000L,
                        open = { ByteArrayInputStream(twoMegs) },
                    ),
                ),
                sink = cancelSink,
            )
            assertTrue(res.cancelled)
        }
        // Give receiver handler thread a brief moment to unwind its socket read and delete .part
        Thread.sleep(150L)
        assertFalse(File(receiverRootDir, "Received/cancelled_sender.bin").exists())
        assertNoPartFilesRemain()

        // 2. Receiver rejects mid-transfer via srv.cancelSession(sessionId) -> 410 "Rejected by Pixel 8", NO retry
        val openCount = AtomicInteger(0)
        eventsJob?.cancel()
        events.clear()
        val cancelTriggerServer = HttpFileServerEngine(
            config = HttpConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(receiverRootDir.canonicalPath),
                deviceName = "Pixel 8",
            ),
            fs = LocalFileSystem(),
        )
        eventsJob = CoroutineScope(Dispatchers.Unconfined).launch {
            cancelTriggerServer.events.collect { ev ->
                events.add(ev)
                if (ev is HttpEvent.PeerSessionStart) {
                    // Cancel synchronously on Unconfined so /api/peer/send receives 410 Gone
                    cancelTriggerServer.cancelSession(ev.sessionId)
                }
            }
        }
        srv.stop()
        cancelTriggerServer.start()
        server = cancelTriggerServer
        val rejectPeer = peer.copy(port = cancelTriggerServer.boundPort)

        PeerHttpClient().use { client ->
            try {
                PeerTransferEngine(client = client).send(
                    peer = rejectPeer,
                    items = listOf(
                        PeerSendItem(
                            relPath = "rejected.bin",
                            size = twoMegs.size.toLong(),
                            mtime = 1_715_000_000_000L,
                            open = {
                                openCount.incrementAndGet()
                                ByteArrayInputStream(twoMegs)
                            },
                        ),
                    ),
                )
                fail("Expected 410 Rejected PeerTransferException")
            } catch (e: PeerTransferException) {
                assertEquals(410, e.httpStatus)
                assertEquals("Rejected by Pixel 8", e.userMessage)
            }
        }
        assertTrue("Post-continue or 410 must never retry more than once", openCount.get() <= 1)
        assertFalse(File(receiverRootDir, "Received/rejected.bin").exists())
        assertNoPartFilesRemain()
    }

    @Test
    fun preContinueConnectionReset_retriesOnceAndCompletesSuccessfully() = runBlocking {
        // Mock server:
        // Connection 1 handles POST /api/peer/begin -> 200 {"session":"s1"}, then drops connection on first POST /api/peer/send BEFORE 100 Continue.
        // Connection 2 handles retried POST /api/peer/send -> 100 Continue -> reads body -> 201 Created, then POST /api/peer/end -> 200 OK.
        val payload = "Retry-safe pre-100-continue payload!".toByteArray(Charsets.UTF_8)
        val sendAttempts = AtomicInteger(0)
        val receivedBytes = ByteArrayOutputStream()

        fun readLineAscii(ins: InputStream): String? {
            val buf = ByteArrayOutputStream()
            var any = false
            while (true) {
                val b = ins.read()
                if (b < 0) return if (any) String(buf.toByteArray(), Charsets.ISO_8859_1) else null
                any = true
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
            }
            return String(buf.toByteArray(), Charsets.ISO_8859_1)
        }

        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { ss ->
            val worker = Thread {
                // Connection 1: /api/peer/begin then abrupt close before 100 Continue on /api/peer/send
                ss.accept().use { s1 ->
                    val ins = s1.getInputStream()
                    val out = s1.getOutputStream()
                    // Read begin headers
                    while (readLineAscii(ins)?.isNotEmpty() == true) { /* consume */ }
                    val beginJson = "{\"session\":\"sess1234\"}"
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${beginJson.length}\r\nConnection: keep-alive\r\n\r\n$beginJson"
                            ).toByteArray(Charsets.ISO_8859_1),
                    )
                    out.flush()

                    // Read first /api/peer/send headers, then close abruptly without sending 100 Continue!
                    val reqLine = readLineAscii(ins)
                    if (reqLine != null && reqLine.contains("/api/peer/send")) {
                        sendAttempts.incrementAndGet()
                        while (readLineAscii(ins)?.isNotEmpty() == true) { /* consume */ }
                    }
                }

                // Connection 2: retried /api/peer/send + /api/peer/end
                ss.accept().use { s2 ->
                    val ins = s2.getInputStream()
                    val out = s2.getOutputStream()

                    val reqLine = readLineAscii(ins).orEmpty()
                    if (reqLine.contains("/api/peer/send")) {
                        sendAttempts.incrementAndGet()
                    }
                    var contentLen = 0
                    while (true) {
                        val h = readLineAscii(ins) ?: break
                        if (h.isEmpty()) break
                        if (h.lowercase().startsWith("content-length:")) {
                            contentLen = h.substringAfter(':').trim().toInt()
                        }
                    }
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()

                    val body = ByteArray(contentLen)
                    var off = 0
                    while (off < contentLen) {
                        val n = ins.read(body, off, contentLen - off)
                        if (n < 0) break
                        off += n
                    }
                    receivedBytes.write(body, 0, off)

                    val createdJson = "{\"ok\":true,\"savedAs\":\"doc.txt\",\"bytes\":$off,\"skipped\":false}"
                    out.write(
                        (
                            "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${createdJson.length}\r\nConnection: keep-alive\r\n\r\n$createdJson"
                            ).toByteArray(Charsets.ISO_8859_1),
                    )
                    out.flush()

                    // Consume /api/peer/end
                    if (readLineAscii(ins) != null) {
                        while (readLineAscii(ins)?.isNotEmpty() == true) { /* consume */ }
                        val endJson = "{\"ok\":true}"
                        out.write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${endJson.length}\r\nConnection: close\r\n\r\n$endJson"
                                ).toByteArray(Charsets.ISO_8859_1),
                        )
                        out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }

            val peer = PeerInfo(
                id = "mock-peer",
                name = "MockPeer",
                host = "127.0.0.1",
                port = ss.localPort,
                version = "1.0.6",
                readOnly = false,
                source = PeerSource.MANUAL,
                lastSeenMs = System.currentTimeMillis(),
            )
            PeerHttpClient().use { client ->
                val res = PeerTransferEngine(client = client).send(
                    peer = peer,
                    items = listOf(
                        PeerSendItem(
                            relPath = "doc.txt",
                            size = payload.size.toLong(),
                            mtime = 1_715_000_000_000L,
                            open = { ByteArrayInputStream(payload) },
                        ),
                    ),
                )
                assertEquals(1, res.files)
                assertEquals(payload.size.toLong(), res.bytes)
            }
            worker.join(3_000L)
        }

        assertEquals(2, sendAttempts.get())
        assertArrayEquals(payload, receivedBytes.toByteArray())
    }
}
