package com.minifile.explorer.core.peer

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class L2capPeerTransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val token = "0123456789abcdef0123456789abcdef"
    private val fs = LocalFileSystem()

    /** Full-duplex in-memory "L2CAP socket" pair. */
    private class Duplex {
        private val aToB = PipedInputStream(256 * 1024)
        private val bToA = PipedInputStream(256 * 1024)
        val aOut: OutputStream = PipedOutputStream(aToB)
        val bOut: OutputStream = PipedOutputStream(bToA)
        val aIn: InputStream = bToA
        val bIn: InputStream = aToB

        fun close() {
            runCatching { aOut.close() }
            runCatching { bOut.close() }
            runCatching { aIn.close() }
            runCatching { bIn.close() }
        }
    }

    private fun fileItem(rel: String, bytes: ByteArray, mtime: Long = 1_700_000_000_000L) =
        PeerSendItem(relPath = rel, size = bytes.size.toLong(), mtime = mtime, open = { ByteArrayInputStream(bytes) })

    private fun dirItem(rel: String) = PeerSendItem(relPath = rel, size = 0L, mtime = 0L, isDirectory = true)

    private fun transfer(
        items: List<PeerSendItem>,
        root: File,
        senderToken: String = token,
        receiverToken: SingleUseToken = SingleUseToken(token),
        sink: ProgressSink = ProgressSink.NOOP,
        listener: L2capReceiveListener = L2capReceiveListener.NOOP,
        receiverCancelled: () -> Boolean = { false },
    ): Pair<Result<PeerSendResult>, L2capReceiveResult> = runBlocking {
        val pipe = Duplex()
        val engine = L2capPeerTransferEngine(chunkBytes = 1024)
        val rx = async(Dispatchers.IO) {
            try {
                engine.receive(
                    input = pipe.bIn,
                    output = pipe.bOut,
                    token = receiverToken,
                    fs = fs,
                    root = VfsPath.Local(root.absolutePath),
                    listener = listener,
                    isCancelled = receiverCancelled,
                )
            } finally {
                runCatching { pipe.bOut.close() }
                runCatching { pipe.bIn.close() }
            }
        }
        val tx = async(Dispatchers.IO) {
            try {
                runCatching {
                    engine.send(pipe.aIn, pipe.aOut, senderToken, "sender-id", "Pixel 9", "iPhone", items, sink)
                }
            } finally {
                runCatching { pipe.aOut.close() }
            }
        }
        withTimeout(20_000) {
            val t = tx.await()
            val r = rx.await()
            pipe.close()
            t to r
        }
    }

    @Test
    fun framing_roundTripsAndRejectsBadLengths() {
        val obj = MiniJson.obj("t" to JsonValue.Str("ok"), "saved" to JsonValue.Str("a/b.txt"))
        val bytes = Mfl2Codec.encodeFrame(obj)
        val n = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        assertEquals(bytes.size - 4, n)
        assertEquals(obj, Mfl2Codec.readFrame(ByteArrayInputStream(bytes)))

        for (bad in listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(0, 1, 0, 1), byteArrayOf(-1, -1, -1, -1))) {
            try {
                Mfl2Codec.readFrame(ByteArrayInputStream(bad + ByteArray(8)))
                fail("expected rejection of ${bad.toList()}")
            } catch (_: Mfl2ProtocolException) {
            }
        }
        // Exactly 65536 is the max allowed header value (body then truncated => EOF, not a protocol error).
        try {
            Mfl2Codec.readFrame(ByteArrayInputStream(byteArrayOf(0, 1, 0, 0, '{'.code.toByte())))
            fail("expected EOF")
        } catch (_: EOFException) {
        }
        // Non-JSON body.
        try {
            Mfl2Codec.readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 'h'.code.toByte(), 'i'.code.toByte())))
            fail("expected protocol error")
        } catch (_: Mfl2ProtocolException) {
        }
    }

    @Test
    fun wireFormat_matchesSpecKeys() {
        val begin = Mfl2Codec.begin(token, "id1", "Pixel", 2, 10L)
        assertEquals("begin", begin.str("t"))
        assertEquals(1, begin.int("v"))
        assertEquals(token, begin.str("tok"))
        assertEquals(2, begin.int("files"))
        assertEquals(10L, begin.long("bytes"))
        val file = Mfl2Codec.file("a/b.bin", 5L, 42L)
        assertEquals(listOf("t", "path", "size", "mtime"), file.entries.keys.toList())
        assertEquals("{\"t\":\"end\",\"ok\":true}", MiniJson.stringify(Mfl2Codec.end(true)))
        assertEquals("{\"t\":\"done\",\"ok\":true,\"files\":3,\"bytes\":9}", MiniJson.stringify(Mfl2Codec.done(true, 3, 9)))
    }

    @Test
    fun nestedDirsMultiFileAndZeroByte_roundTrip() {
        val root = tmp.newFolder("share")
        val big = ByteArray(70_000) { (it % 251).toByte() }
        val items = listOf(
            dirItem("Album/Empty"),
            fileItem("Album/sub/photo.jpg", big),
            fileItem("Album/zero.txt", ByteArray(0)),
            fileItem("notes 100%.txt", "hello".toByteArray()),
        )
        val progress = ArrayList<Long>()
        val sink = object : ProgressSink {
            override fun onProgress(p: OperationProgress) {
                progress += p.processedBytes
            }
            override fun isCancelled() = false
        }
        val savedEvents = ArrayList<String>()
        val listener = object : L2capReceiveListener {
            override fun onFileSaved(savedRelPath: String, path: VfsPath, bytes: Long, durationMs: Long) {
                savedEvents += savedRelPath
            }
        }
        val (tx, rx) = transfer(items, root, sink = sink, listener = listener)
        val sent = tx.getOrThrow()
        assertEquals(3, sent.files)
        assertEquals(1, sent.dirs)
        assertEquals(big.size + 5L, sent.bytes)
        assertFalse(sent.cancelled)
        assertTrue(rx.ok)
        assertEquals("Pixel 9", rx.senderName)
        assertEquals(3, rx.files)
        assertEquals(listOf("Album/sub/photo.jpg", "Album/zero.txt", "notes 100%.txt"), savedEvents)
        val inbox = File(root, "Received")
        assertTrue(File(inbox, "Album/Empty").isDirectory)
        assertArrayEquals(big, File(inbox, "Album/sub/photo.jpg").readBytes())
        assertEquals(0L, File(inbox, "Album/zero.txt").length())
        assertEquals("hello", File(inbox, "notes 100%.txt").readText())
        assertEquals(1_700_000_000_000L, File(inbox, "Album/sub/photo.jpg").lastModified())
        assertTrue("live byte progress", progress.size > 10 && progress.last() == big.size + 5L)
        // No temp leftovers.
        assertTrue(inbox.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test
    fun existingFiles_areRenamedNeverOverwritten() {
        val root = tmp.newFolder("share")
        val inbox = File(root, "Received").apply { mkdirs() }
        File(inbox, "a.txt").writeText("original")
        val (tx, rx) = transfer(listOf(fileItem("a.txt", "new".toByteArray())), root)
        tx.getOrThrow()
        assertTrue(rx.ok)
        assertEquals("original", File(inbox, "a.txt").readText())
        assertEquals(1, rx.saved.size)
        val savedName = rx.saved.single()
        assertTrue(savedName, savedName != "a.txt" && savedName.startsWith("a") && savedName.endsWith(".txt"))
        assertEquals("new", File(inbox, savedName).readText())
    }

    @Test
    fun wrongToken_isRejectedBeforeAnyWrite() {
        val root = tmp.newFolder("share")
        val (tx, rx) = transfer(
            listOf(fileItem("x.bin", ByteArray(10))),
            root,
            senderToken = "ffffffffffffffffffffffffffffffff",
        )
        val err = tx.exceptionOrNull()
        assertTrue(err is PeerTransferException)
        assertTrue((err as PeerTransferException).userMessage, err.userMessage.contains("token"))
        assertFalse(rx.ok)
        assertFalse(File(root, "Received/x.bin").exists())
    }

    @Test
    fun token_isSingleUse() {
        val gate = SingleUseToken(token)
        assertFalse(gate.consume("nope"))
        assertFalse(gate.consume(null))
        assertTrue(gate.consume(token))
        assertFalse(gate.consume(token))
        assertFalse(SingleUseToken("short").consume("short"))
    }

    @Test
    fun traversalPath_isRejectedWithErr() {
        val root = tmp.newFolder("share")
        val (tx, rx) = transfer(listOf(fileItem("../evil.txt", "x".toByteArray())), root)
        assertTrue(tx.exceptionOrNull() is PeerTransferException)
        assertFalse(rx.ok)
        assertFalse(File(root, "evil.txt").exists())
    }

    @Test
    fun senderCancellation_midFile_returnsCancelledAndLeavesNoPartial() {
        val root = tmp.newFolder("share")
        val cancel = AtomicBoolean(false)
        val sink = object : ProgressSink {
            override fun onProgress(p: OperationProgress) {
                if (p.processedBytes > 4_000) cancel.set(true)
            }
            override fun isCancelled() = cancel.get()
        }
        val (tx, rx) = transfer(listOf(fileItem("big.bin", ByteArray(50_000))), root, sink = sink)
        assertTrue(tx.getOrThrow().cancelled)
        assertFalse(rx.ok)
        val inbox = File(root, "Received")
        assertFalse(File(inbox, "big.bin").exists())
        assertTrue(inbox.walkTopDown().none { it.isFile })
    }

    @Test
    fun senderCancellation_betweenItems_sendsEndFalse() {
        val root = tmp.newFolder("share")
        val cancel = AtomicBoolean(false)
        val sink = object : ProgressSink {
            override fun onProgress(p: OperationProgress) {
                if (p.processedItems >= 1) cancel.set(true)
            }
            override fun isCancelled() = cancel.get()
        }
        val (tx, rx) = transfer(
            listOf(fileItem("one.txt", "1".toByteArray()), fileItem("two.txt", "2".toByteArray())),
            root,
            sink = sink,
        )
        val res = tx.getOrThrow()
        assertTrue(res.cancelled)
        assertEquals(1, res.files)
        assertFalse(rx.ok)
        assertTrue(rx.cancelled)
        assertTrue(File(root, "Received/one.txt").exists())
        assertFalse(File(root, "Received/two.txt").exists())
    }

    @Test
    fun receiverCancellation_stopsSenderWithError() {
        val root = tmp.newFolder("share")
        val (tx, rx) = transfer(
            listOf(fileItem("big.bin", ByteArray(40_000))),
            root,
            receiverCancelled = { true },
        )
        assertTrue(tx.isFailure)
        assertFalse(rx.ok)
        assertTrue(rx.cancelled)
    }

    @Test
    fun receiver_rejectsOversizedHeaderAndBadVersion() = runBlocking {
        val root = tmp.newFolder("share")
        val engine = L2capPeerTransferEngine()
        val out = ByteArrayOutputStream()
        val oversized = ByteArrayInputStream(byteArrayOf(0, 1, 0, 1) + ByteArray(16))
        val r1 = engine.receive(oversized, out, SingleUseToken(token), fs, VfsPath.Local(root.absolutePath))
        assertFalse(r1.ok)
        val reply = Mfl2Codec.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertEquals("err", reply.str("t"))

        val v2 = MiniJson.obj(
            "t" to JsonValue.Str("begin"),
            "v" to JsonValue.Num(2),
            "tok" to JsonValue.Str(token),
            "id" to JsonValue.Str("x"),
            "name" to JsonValue.Str("x"),
            "files" to JsonValue.Num(0),
            "bytes" to JsonValue.Num(0),
        )
        val out2 = ByteArrayOutputStream()
        val r2 = engine.receive(
            ByteArrayInputStream(Mfl2Codec.encodeFrame(v2)),
            out2,
            SingleUseToken(token),
            fs,
            VfsPath.Local(root.absolutePath),
        )
        assertFalse(r2.ok)
        assertEquals("err", Mfl2Codec.readFrame(ByteArrayInputStream(out2.toByteArray())).str("t"))
    }

    @Test
    fun receiver_scriptedIosSender_withMtimeZeroAndMissingFields() = runBlocking {
        // Byte-level script as an iOS sender would emit it (mtime 0 ⇒ ignored).
        val root = tmp.newFolder("share")
        val payload = "abc".toByteArray()
        val script = ByteArrayOutputStream().apply {
            write(Mfl2Codec.encodeFrame(MiniJson.parse("{\"t\":\"begin\",\"v\":1,\"tok\":\"$token\",\"id\":\"ios-1\",\"name\":\"iPhone\",\"files\":1,\"bytes\":3}") as JsonValue.Obj))
            write(Mfl2Codec.encodeFrame(Mfl2Codec.mkdir("Docs")))
            write(Mfl2Codec.encodeFrame(Mfl2Codec.file("Docs/a.txt", 3, 0)))
            write(payload)
            write(Mfl2Codec.encodeFrame(Mfl2Codec.end(true)))
        }.toByteArray()
        val out = ByteArrayOutputStream()
        val res = L2capPeerTransferEngine().receive(
            ByteArrayInputStream(script),
            out,
            SingleUseToken(token),
            fs,
            VfsPath.Local(root.absolutePath),
        )
        assertTrue(res.error, res.ok)
        assertEquals("iPhone", res.senderName)
        val replies = ByteArrayInputStream(out.toByteArray())
        assertEquals("ok", Mfl2Codec.readFrame(replies).str("t"))
        assertEquals("ok", Mfl2Codec.readFrame(replies).str("t"))
        val fileOk = Mfl2Codec.readFrame(replies)
        assertEquals("ok", fileOk.str("t"))
        assertEquals("Docs/a.txt", fileOk.str("saved"))
        val done = Mfl2Codec.readFrame(replies)
        assertEquals("done", done.str("t"))
        assertTrue(done.bool("ok"))
        assertEquals(1, done.int("files"))
        assertEquals(3L, done.long("bytes"))
        assertEquals("abc", File(root, "Received/Docs/a.txt").readText())
    }

    @Test
    fun abruptDisconnect_midFile_isReportedAndCleanedUp() = runBlocking {
        val root = tmp.newFolder("share")
        val script = ByteArrayOutputStream().apply {
            write(Mfl2Codec.encodeFrame(Mfl2Codec.begin(token, "i", "iPhone", 1, 100)))
            write(Mfl2Codec.encodeFrame(Mfl2Codec.file("cut.bin", 100, 0)))
            write(ByteArray(40))
        }.toByteArray()
        val res = L2capPeerTransferEngine().receive(
            ByteArrayInputStream(script),
            ByteArrayOutputStream(),
            SingleUseToken(token),
            fs,
            VfsPath.Local(root.absolutePath),
        )
        assertFalse(res.ok)
        assertTrue(File(root, "Received").walkTopDown().none { it.isFile })
    }
}
