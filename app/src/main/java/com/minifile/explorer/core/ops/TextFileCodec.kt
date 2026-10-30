package com.minifile.explorer.core.ops

import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.VfsPath
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class LineEnding(val sequence: String, val label: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
    CR("\r", "CR"),
}

enum class BomKind(val bytes: ByteArray, val charsetName: String) {
    NONE(byteArrayOf(), ""),
    UTF8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), "UTF-8"),
    UTF16_LE(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "UTF-16LE"),
    UTF16_BE(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), "UTF-16BE"),
}

data class DetectedText(
    val charset: Charset,
    val bom: BomKind,
    val eol: LineEnding,
)

data class DecodedTextFile(
    val text: String,
    val charset: Charset,
    val bom: BomKind,
    val eol: LineEnding,
)

object TextFileCodec {

    val SUPPORTED_CHARSET_NAMES: List<String> = listOf(
        "UTF-8",
        "UTF-16LE",
        "UTF-16BE",
        "ISO-8859-1",
        "windows-1252",
        "GBK",
        "Shift_JIS",
    ).filter { runCatching { Charset.isSupported(it) }.getOrDefault(false) }

    fun isProbablyText(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        if (hasPrefix(head, BomKind.UTF8.bytes) ||
            hasPrefix(head, BomKind.UTF16_LE.bytes) ||
            hasPrefix(head, BomKind.UTF16_BE.bytes)
        ) {
            return true
        }
        val limit = minOf(head.size, 8192)
        for (i in 0 until limit) {
            if (head[i] == 0.toByte()) return false
        }
        return true
    }

    fun detect(bytes: ByteArray): DetectedText {
        val (bom, charset) = when {
            hasPrefix(bytes, BomKind.UTF8.bytes) -> BomKind.UTF8 to Charsets.UTF_8
            hasPrefix(bytes, BomKind.UTF16_LE.bytes) -> BomKind.UTF16_LE to Charsets.UTF_16LE
            hasPrefix(bytes, BomKind.UTF16_BE.bytes) -> BomKind.UTF16_BE to Charsets.UTF_16BE
            isValidUtf8(bytes) -> BomKind.NONE to Charsets.UTF_8
            else -> BomKind.NONE to Charsets.ISO_8859_1
        }
        val payload = bytes.copyOfRange(bom.bytes.size, bytes.size)
        val rawStr = String(payload, charset)
        val eol = detectLineEnding(rawStr)
        return DetectedText(charset = charset, bom = bom, eol = eol)
    }

    fun decode(bytes: ByteArray, overrideCharset: Charset? = null): DecodedTextFile {
        val detected = detect(bytes)
        val charset = overrideCharset ?: detected.charset
        val bom = if (overrideCharset == null || overrideCharset == detected.charset) {
            detected.bom
        } else {
            BomKind.NONE
        }
        val offset = if (hasPrefix(bytes, bom.bytes)) bom.bytes.size else 0
        val rawStr = String(bytes, offset, bytes.size - offset, charset)
        val eol = detectLineEnding(rawStr)
        val normalized = normalizeToLf(rawStr)
        return DecodedTextFile(
            text = normalized,
            charset = charset,
            bom = bom,
            eol = eol,
        )
    }

    fun encode(
        text: String,
        charset: Charset = Charsets.UTF_8,
        eol: LineEnding = LineEnding.LF,
        bom: BomKind = BomKind.NONE,
    ): ByteArray {
        val lfNormalized = normalizeToLf(text)
        val converted = if (eol == LineEnding.LF) {
            lfNormalized
        } else {
            lfNormalized.replace("\n", eol.sequence)
        }
        val body = converted.toByteArray(charset)
        if (bom == BomKind.NONE || bom.bytes.isEmpty()) return body
        return bom.bytes + body
    }

    suspend fun readFile(
        fs: FileSystem,
        path: VfsPath,
        overrideCharset: Charset? = null,
        maxBytes: Int = 20 * 1024 * 1024,
    ): DecodedTextFile {
        val bytes = readCappedBytes(fs, path, maxBytes)
        return decode(bytes, overrideCharset)
    }

    suspend fun saveFile(
        fs: FileSystem,
        path: VfsPath,
        text: String,
        charset: Charset = Charsets.UTF_8,
        eol: LineEnding = LineEnding.LF,
        bom: BomKind = BomKind.NONE,
    ) {
        val payload = encode(text, charset, eol, bom)
        if (path is VfsPath.Local) {
            val target = File(path.normalized)
            val parent = target.parentFile ?: throw IOException("Missing parent directory")
            val tmp = File(parent, ".${target.name}.tmp-${UUID.randomUUID().toString().take(8)}")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(payload)
                    out.fd.sync()
                }
                try {
                    Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: IOException) {
                    // Fallback when filesystem (e.g. FAT/FUSE) rejects ATOMIC_MOVE
                    FileOutputStream(target, false).use { out ->
                        out.write(payload)
                        out.flush()
                    }
                    tmp.delete()
                }
            } finally {
                if (tmp.exists()) {
                    runCatching { tmp.delete() }
                }
            }
        } else {
            fs.openOutput(path, append = false).use { out ->
                out.write(payload)
                out.flush()
            }
        }
    }

    private fun readCappedBytes(fs: FileSystem, path: VfsPath, maxBytes: Int): ByteArray {
        fs.openInput(path).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < maxBytes) {
                val toRead = minOf(buf.size, maxBytes - total)
                val n = input.read(buf, 0, toRead)
                if (n < 0) break
                if (n > 0) {
                    out.write(buf, 0, n)
                    total += n
                }
            }
            return out.toByteArray()
        }
    }

    private fun detectLineEnding(s: String): LineEnding {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '\r' -> {
                    if (i + 1 < s.length && s[i + 1] == '\n') {
                        crlf++
                        i++
                    } else {
                        cr++
                    }
                }

                '\n' -> lf++
            }
            i++
        }
        return when {
            crlf > 0 && crlf >= lf && crlf >= cr -> LineEnding.CRLF
            cr > 0 && cr > lf && cr > crlf -> LineEnding.CR
            else -> LineEnding.LF
        }
    }

    private fun normalizeToLf(s: String): String =
        s.replace("\r\n", "\n").replace('\r', '\n')

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasPrefix(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (prefix.isEmpty() || bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) return false
        }
        return true
    }
}
