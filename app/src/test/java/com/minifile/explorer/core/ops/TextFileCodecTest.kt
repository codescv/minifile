package com.minifile.explorer.core.ops

import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextFileCodecTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun detectsBomUtf8Utf16LineEndingsAndBinaryFiles() = runBlocking {
        // UTF-8 with BOM and CRLF
        val utf8BomBytes = TextFileCodec.encode(
            text = "line1\nline2 — привет",
            charset = Charsets.UTF_8,
            eol = LineEnding.CRLF,
            bom = BomKind.UTF8,
        )
        val decodedUtf8 = TextFileCodec.decode(utf8BomBytes)
        assertEquals(Charsets.UTF_8, decodedUtf8.charset)
        assertEquals(BomKind.UTF8, decodedUtf8.bom)
        assertEquals(LineEnding.CRLF, decodedUtf8.eol)
        assertEquals("line1\nline2 — привет", decodedUtf8.text)
        assertArrayEquals(
            utf8BomBytes,
            TextFileCodec.encode(decodedUtf8.text, decodedUtf8.charset, decodedUtf8.eol, decodedUtf8.bom),
        )

        // UTF-16LE with BOM
        val utf16Bytes = TextFileCodec.encode(
            text = "Hello 中文\nWorld",
            charset = Charsets.UTF_16LE,
            eol = LineEnding.LF,
            bom = BomKind.UTF16_LE,
        )
        assertTrue(TextFileCodec.isProbablyText(utf16Bytes))
        val decoded16 = TextFileCodec.decode(utf16Bytes)
        assertEquals(Charsets.UTF_16LE, decoded16.charset)
        assertEquals(BomKind.UTF16_LE, decoded16.bom)
        assertEquals("Hello 中文\nWorld", decoded16.text)

        // Binary NUL sniff (without UTF-16 BOM)
        val binary = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x0D, 0x0A)
        assertFalse(TextFileCodec.isProbablyText(binary))

        // Atomic save roundtrip on LocalFileSystem
        val fs = LocalFileSystem()
        val dir = VfsPath.Local(tempFolder.newFolder("editor").absolutePath)
        val file = fs.createFile(dir, "config.ini")
        TextFileCodec.saveFile(fs, file, "key=value\nport=2121", Charsets.UTF_8, LineEnding.CRLF, BomKind.NONE)
        val reloaded = TextFileCodec.readFile(fs, file)
        assertEquals("key=value\nport=2121", reloaded.text)
        assertEquals(LineEnding.CRLF, reloaded.eol)
    }
}
