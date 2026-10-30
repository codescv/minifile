package com.minifile.explorer.core.ftp

import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Cross-component integration harness (`ARCHITECTURE.md` §8.4).
 * Skipped during normal unit test runs unless `MINIFILE_FTP_SERVE=1`.
 */
class FtpEngineServeTest {

    @Test
    fun serveUntilStopFile() {
        Assume.assumeTrue(
            "Set MINIFILE_FTP_SERVE=1 to run cross-component FTP server harness",
            System.getenv("MINIFILE_FTP_SERVE") == "1",
        )

        val port = System.getenv("MINIFILE_FTP_PORT")?.toIntOrNull() ?: 0
        val rootDir = System.getenv("MINIFILE_FTP_ROOT")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).apply { mkdirs() } }
            ?: Files.createTempDirectory("minifile-ftp-serve-").toFile()

        val readOnly = System.getenv("MINIFILE_FTP_READONLY") == "1"
        val user = System.getenv("MINIFILE_FTP_USER")?.takeIf { it.isNotEmpty() }
        val pass = System.getenv("MINIFILE_FTP_PASS") ?: ""
        val auth = if (user != null) FtpAuth.Credentials(user, pass) else FtpAuth.Anonymous

        val pasvPorts = System.getenv("MINIFILE_FTP_PASV_PORTS")
            ?.let { spec ->
                val parts = spec.split('-', ':')
                val lo = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val hi = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: lo
                if (lo != null && hi != null && lo <= hi) lo..hi else null
            } ?: (50000..50100)

        val readyFile = System.getenv("MINIFILE_FTP_READY_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
        val stopFile = System.getenv("MINIFILE_FTP_STOP_FILE")?.takeIf { it.isNotBlank() }?.let(::File)

        val engine = FtpServerEngine(
            config = FtpConfig(
                port = port,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(rootDir.canonicalPath),
                readOnly = readOnly,
                auth = auth,
                passivePorts = pasvPorts,
            ),
            fs = LocalFileSystem(),
            externalIpProvider = { listOf("127.0.0.1") },
        )

        engine.start()
        try {
            val readyLine = "READY port=${engine.boundPort} root=${rootDir.canonicalPath}"
            println(readyLine)
            System.out.flush()
            if (readyFile != null) {
                readyFile.parentFile?.mkdirs()
                readyFile.writeText("$readyLine\n", Charsets.UTF_8)
            }

            if (stopFile != null) {
                val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
                while (!stopFile.exists() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(250L)
                }
            }
        } finally {
            engine.stop()
        }
    }
}
