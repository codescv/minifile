package com.minifile.explorer.core.ops

import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ChecksumCalculatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fs = LocalFileSystem()
    private val calculator = ChecksumCalculator(fs)

    @Test
    fun computesKnownMd5AndSha256Vectors() = runBlocking {
        val dir = VfsPath.Local(tempFolder.newFolder("hashes").absolutePath)
        val empty = fs.createFile(dir, "empty.bin")
        val abc = fs.createFile(dir, "abc.txt")
        fs.openOutput(abc).use { it.write("abc".toByteArray(Charsets.US_ASCII)) }

        assertEquals("d41d8cd98f00b204e9800998ecf8427e", calculator.digest(empty, "MD5"))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            calculator.digest(empty, "SHA-256"),
        )
        assertEquals("900150983cd24fb0d6963f7d28e17f72", calculator.digest(abc, "MD5"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            calculator.digest(abc, "SHA-256"),
        )
    }

    @Test
    fun honoursCancellation() = runBlocking {
        val dir = VfsPath.Local(tempFolder.newFolder("cancel").absolutePath)
        val big = fs.createFile(dir, "big.bin")
        fs.openOutput(big).use { it.write(ByteArray(512 * 1024)) }

        val cancelledSink = object : ProgressSink {
            var calls = 0
            override fun onProgress(p: OperationProgress) {
                calls++
            }
            override fun isCancelled(): Boolean = calls >= 1
        }

        try {
            calculator.digest(big, "SHA-256", cancelledSink)
            fail("Expected IOException on cancellation")
        } catch (_: IOException) {
            // Expected
        }
    }
}
