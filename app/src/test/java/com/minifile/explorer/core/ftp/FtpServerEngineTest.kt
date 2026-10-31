package com.minifile.explorer.core.ftp

import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.LanAddressResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files

class FtpServerEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var activeEngine: FtpServerEngine? = null

    @After
    fun tearDown() {
        activeEngine?.stop()
        activeEngine = null
    }

    private class TestFtpClient(host: String, port: Int) : Closeable {
        val socket = Socket(host, port).apply { soTimeout = 10_000 }
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

        fun readResponse(): Pair<Int, String> {
            val first = reader.readLine() ?: throw AssertionError("Server closed control stream unexpectedly")
            val code = first.take(3).toInt()
            if (first.length >= 4 && first[3] == '-') {
                val lines = mutableListOf(first)
                val endPrefix = "$code "
                while (true) {
                    val next = reader.readLine() ?: break
                    lines.add(next)
                    if (next.startsWith(endPrefix)) break
                }
                return code to lines.joinToString("\n")
            }
            return code to first
        }

        fun cmd(line: String): Pair<Int, String> {
            writer.write("$line\r\n")
            writer.flush()
            return readResponse()
        }

        fun openPasvSocket(): Socket {
            val (code, text) = cmd("PASV")
            assertEquals("PASV failed: $text", 227, code)
            val inside = text.substringAfter('(').substringBefore(')')
            val nums = inside.split(',').map { it.trim().toInt() }
            val ip = "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}"
            val port = (nums[4] shl 8) or nums[5]
            assertEquals("127.0.0.1", ip)
            return Socket(ip, port).apply { soTimeout = 10_000 }
        }

        fun openEpsvSocket(): Socket {
            val (code, text) = cmd("EPSV")
            assertEquals("EPSV failed: $text", 229, code)
            val port = text.substringAfter("(|||").substringBefore("|)").toInt()
            return Socket("127.0.0.1", port).apply { soTimeout = 10_000 }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    @Test
    fun fullProtocolFlowAuthPasvEpsvUtf8ListingsRestTransfersRenameAndSymlinkJail() {
        val rootDir = tempFolder.newFolder("ftp_srv")
        val outsideDir = tempFolder.newFolder("ftp_outside")
        File(outsideDir, "secret.txt").writeText("classified")
        Files.createSymbolicLink(File(rootDir, "badlink").toPath(), outsideDir.toPath())

        val engine = FtpServerEngine(
            config = FtpConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(rootDir.absolutePath),
                auth = FtpAuth.Credentials("alice", "s3cr3t"),
                passivePorts = 0..0,
            ),
            fs = LocalFileSystem(),
        )
        engine.start()
        activeEngine = engine

        TestFtpClient("127.0.0.1", engine.boundPort).use { c ->
            val (bannerCode, banner) = c.readResponse()
            assertEquals(220, bannerCode)
            assertTrue(banner.contains("MiniFile"))

            // Unauthenticated command rejected
            assertEquals(530, c.cmd("PWD").first)

            // Wrong password rejected
            assertEquals(331, c.cmd("USER alice").first)
            assertEquals(530, c.cmd("PASS wrong").first)

            // Valid credentials accepted
            assertEquals(331, c.cmd("USER alice").first)
            assertEquals(230, c.cmd("PASS s3cr3t").first)

            // FEAT & OPTS UTF8 ON & SYST
            val (featCode, featBody) = c.cmd("FEAT")
            assertEquals(211, featCode)
            for (f in listOf("UTF8", "MLSD", "MLST", "SIZE", "MDTM", "REST STREAM", "EPSV", "PASV", "TVFS")) {
                assertTrue("Missing FEAT $f in:\n$featBody", featBody.contains(f))
            }
            assertEquals(200, c.cmd("OPTS UTF8 ON").first)
            assertEquals(215, c.cmd("SYST").first)

            // PWD, MKD, CWD, CDUP
            assertEquals("257 \"/\" is current directory", c.cmd("PWD").second)
            val (mkdCode, mkdResp) = c.cmd("MKD /Docs")
            assertEquals(257, mkdCode)
            assertTrue(mkdResp.contains("\"/Docs\""))
            assertEquals(250, c.cmd("CWD /Docs").first)
            assertTrue(c.cmd("PWD").second.startsWith("257 \"/Docs\""))
            assertEquals(250, c.cmd("CDUP").first)
            assertTrue(c.cmd("PWD").second.startsWith("257 \"/\""))

            // Missing parent MKD / STOR -> 550
            assertEquals(550, c.cmd("MKD /MissingParent/Child").first)

            // Upload UTF-8 filename via PASV + STOR
            val utfName = "测试 файл ✓ (1).txt"
            val payload = "0123456789ABCDEF-UTF8-✓"
            c.openPasvSocket().use { data ->
                assertEquals(150, c.cmd("STOR /Docs/$utfName").first)
                data.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
                data.shutdownOutput()
            }
            assertEquals(226, c.readResponse().first)

            // APPE appends
            c.openEpsvSocket().use { data ->
                assertEquals(150, c.cmd("APPE /Docs/$utfName").first)
                data.getOutputStream().write("!".toByteArray(Charsets.UTF_8))
                data.shutdownOutput()
            }
            assertEquals(226, c.readResponse().first)

            // SIZE & MDTM
            val fullExpected = "$payload!"
            val expectedBytes = fullExpected.toByteArray(Charsets.UTF_8)
            assertEquals("213 ${expectedBytes.size}", c.cmd("SIZE /Docs/$utfName").second)
            assertEquals(550, c.cmd("SIZE /Docs").first) // SIZE on dir -> 550
            assertEquals(213, c.cmd("MDTM /Docs/$utfName").first)
            assertEquals(550, c.cmd("MDTM /Docs/missing.txt").first)

            // MLSD & LIST & NLST
            val mlsdText = c.openEpsvSocket().use { data ->
                assertEquals(150, c.cmd("MLSD /Docs").first)
                String(data.getInputStream().readBytes(), Charsets.UTF_8)
            }
            assertEquals(226, c.readResponse().first)
            assertTrue(mlsdText.contains("type=file;size=${expectedBytes.size};"))
            assertTrue(mlsdText.contains(utfName))

            val listText = c.openPasvSocket().use { data ->
                assertEquals(150, c.cmd("LIST /Docs").first)
                String(data.getInputStream().readBytes(), Charsets.UTF_8)
            }
            assertEquals(226, c.readResponse().first)
            assertTrue(listText.contains(utfName))

            val nlstText = c.openEpsvSocket().use { data ->
                assertEquals(150, c.cmd("NLST /Docs").first)
                String(data.getInputStream().readBytes(), Charsets.UTF_8)
            }
            assertEquals(226, c.readResponse().first)
            assertEquals(utfName, nlstText.trim())

            // REST + RETR partial download
            assertEquals(350, c.cmd("REST 10").first)
            val resumed = c.openPasvSocket().use { data ->
                assertEquals(150, c.cmd("RETR /Docs/$utfName").first)
                String(data.getInputStream().readBytes(), Charsets.UTF_8)
            }
            assertEquals(226, c.readResponse().first)
            assertEquals(fullExpected.substring(10), resumed)

            // RNFR + RNTO + DELE + RMD
            assertEquals(350, c.cmd("RNFR /Docs/$utfName").first)
            assertEquals(250, c.cmd("RNTO /Docs/renamed.txt").first)
            assertEquals(250, c.cmd("DELE /Docs/renamed.txt").first)
            assertEquals(250, c.cmd("RMD /Docs").first)
            assertFalse(File(rootDir, "Docs").exists())

            // Symlink escape rejected with 550
            assertEquals(550, c.cmd("CWD /badlink").first)
            assertEquals(550, c.cmd("RETR /badlink/secret.txt").first)

            // '..' clamps safely to root
            assertEquals(250, c.cmd("CWD /../../..").first)
            assertTrue(c.cmd("PWD").second.startsWith("257 \"/\""))

            assertEquals(221, c.cmd("QUIT").first)
        }
    }

    @Test
    fun readOnlyModeAndIdleTimeoutEnforced() {
        val rootDir = tempFolder.newFolder("ftp_ro")
        File(rootDir, "keep.txt").writeText("immutable")

        val engine = FtpServerEngine(
            config = FtpConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(rootDir.absolutePath),
                readOnly = true,
                auth = FtpAuth.Anonymous,
                idleTimeoutMs = 400L,
            ),
            fs = LocalFileSystem(),
        )
        engine.start()
        activeEngine = engine

        TestFtpClient("127.0.0.1", engine.boundPort).use { c ->
            assertEquals(220, c.readResponse().first)
            assertEquals(331, c.cmd("USER anonymous").first)
            assertEquals(230, c.cmd("PASS guest@").first)

            assertEquals(550, c.cmd("MKD /NewDir").first)
            assertEquals(550, c.cmd("DELE /keep.txt").first)
            assertEquals(550, c.cmd("STOR /new.txt").first)
            assertTrue(File(rootDir, "keep.txt").exists())

            // Wait past 400ms idle timeout -> server sends 421
            Thread.sleep(650L)
            val (timeoutCode, _) = c.readResponse()
            assertEquals(421, timeoutCode)
        }
    }

    @Test
    fun passiveRangeBoundedReturns425WhenExhaustedAndNeverFallsBackToRandomPort() {
        val rootDir = tempFolder.newFolder("ftp_pasv_bound")
        File(rootDir, "hello.txt").writeText("bounded")

        // Hold an occupied listening ServerSocket on wildcard address so the 1-port passive range is exhausted
        java.net.ServerSocket(0).use { blocker ->
            val occupiedPort = blocker.localPort
            val engine = FtpServerEngine(
                config = FtpConfig(
                    port = 0,
                    bindAddress = "127.0.0.1",
                    root = VfsPath.Local(rootDir.absolutePath),
                    auth = FtpAuth.Anonymous,
                    passivePorts = occupiedPort..occupiedPort,
                ),
                fs = LocalFileSystem(),
            )
            engine.start()
            activeEngine = engine

            TestFtpClient("127.0.0.1", engine.boundPort).use { c ->
                assertEquals(220, c.readResponse().first)
                assertEquals(331, c.cmd("USER anonymous").first)
                assertEquals(230, c.cmd("PASS guest@").first)

                // Both PASV and EPSV must reject with 425 instead of binding outside passivePorts
                assertEquals(425, c.cmd("PASV").first)
                assertEquals(425, c.cmd("EPSV").first)
            }
        }
    }

    @Test
    fun strictLanAndLoopbackFirewallAllowsOnlyLocalPrivateLinkLocalCgnatAndUla() {
        val allowedIps = listOf(
            "127.0.0.1",
            "127.255.255.254",
            "10.0.0.1",
            "10.255.255.254",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.0.1",
            "192.168.43.1",
            "169.254.10.20",
            "100.64.0.1",
            "100.127.255.254",
            "::1",
            "fe80::1234:5678",
            "fc00::1",
            "fd12:3456:789a::42",
        )
        for (ip in allowedIps) {
            assertTrue(
                "Expected $ip to be allowed",
                FtpServerEngine.isAllowedClientAddress(InetAddress.getByName(ip)),
            )
        }

        val blockedIps = listOf(
            "0.0.0.0",
            "8.8.8.8",
            "1.1.1.1",
            "100.63.255.255",
            "100.128.0.0",
            "172.15.255.255",
            "172.32.0.1",
            "192.167.1.1",
            "192.0.2.10",
            "224.0.0.1",
            "255.255.255.255",
            "::",
            "2001:4860:4860::8888",
            "2408:8207:1234::1",
            "2607:f8b0:4004:800::200e",
            "fe00::1",
            "ff02::1",
        )
        for (ip in blockedIps) {
            assertFalse(
                "Expected $ip to be blocked",
                FtpServerEngine.isAllowedClientAddress(InetAddress.getByName(ip)),
            )
        }
    }

    @Test
    fun lanAddressResolverExcludesCellularAndVpnInterfacesAndPublicIps() {
        val blockedIfaces = listOf(
            "rmnet_data0",
            "rmnet0",
            "r_rmnet_data1",
            "ccmni0",
            "pdp_ip0",
            "wwan0",
            "tun0",
            "tap0",
            "ppp0",
            "wg0",
            "ipsec0",
            "clat4",
            "v4-rmnet_data0",
        )
        for (iface in blockedIfaces) {
            assertTrue("Expected $iface to be excluded", LanAddressResolver.isExcludedInterface(iface))
            assertFalse("Expected $iface not allowed as LAN", LanAddressResolver.isAllowedLocalInterface(iface))
        }

        val allowedIfaces = listOf(
            "wlan0",
            "wlan1",
            "ap0",
            "swlan0",
            "softap0",
            "rndis0",
            "usb0",
            "ncm0",
            "eth0",
            "bridge0",
        )
        for (iface in allowedIfaces) {
            assertFalse(LanAddressResolver.isExcludedInterface(iface))
            assertTrue("Expected $iface allowed as LAN", LanAddressResolver.isAllowedLocalInterface(iface))
        }

        assertTrue(LanAddressResolver.isAllowedLanIpv4(InetAddress.getByName("192.168.1.42") as Inet4Address))
        assertTrue(LanAddressResolver.isAllowedLanIpv4(InetAddress.getByName("100.64.10.5") as Inet4Address))
        assertTrue(LanAddressResolver.isAllowedLanIpv4(InetAddress.getByName("169.254.5.6") as Inet4Address))
        assertFalse(LanAddressResolver.isAllowedLanIpv4(InetAddress.getByName("127.0.0.1") as Inet4Address))
        assertFalse(LanAddressResolver.isAllowedLanIpv4(InetAddress.getByName("8.8.8.8") as Inet4Address))
    }
}
