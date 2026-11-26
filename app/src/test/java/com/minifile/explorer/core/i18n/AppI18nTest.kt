package com.minifile.explorer.core.i18n

import com.minifile.explorer.core.diag.DiagnosticExport
import com.minifile.explorer.core.feedback.AgentFeedbackBuilder
import com.minifile.explorer.core.feedback.FeedbackMetadata
import com.minifile.explorer.core.gdrive.GoogleOAuthLoopback
import com.minifile.explorer.core.http.HttpConfig
import com.minifile.explorer.core.http.HttpFileServerEngine
import com.minifile.explorer.core.model.InMemoryKeyValueStore
import com.minifile.explorer.core.peer.HostDecision
import com.minifile.explorer.core.peer.LinkBlocker
import com.minifile.explorer.core.peer.PeerDiscoveryEngine
import com.minifile.explorer.core.peer.PeerTransferException
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class AppI18nTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        // Restore global i18n state so sibling tests (e.g. NearbyStatusCopyTest) see English default.
        AppI18n.setResolved(AppLanguage.SYSTEM, ResolvedLanguage.EN)
    }

    @Test
    fun resolveLanguage_systemDefaultMapsChineseJapaneseAndFallbackToEnglish() {
        assertEquals(ResolvedLanguage.ZH, AppI18n.resolve(AppLanguage.SYSTEM, Locale.SIMPLIFIED_CHINESE))
        assertEquals(ResolvedLanguage.ZH, AppI18n.resolve(AppLanguage.SYSTEM, Locale.TRADITIONAL_CHINESE))
        assertEquals(ResolvedLanguage.ZH, AppI18n.resolveLanguageTag(AppLanguage.SYSTEM, "zh-Hans-CN"))
        assertEquals(ResolvedLanguage.ZH, AppI18n.resolveLanguageTag(AppLanguage.SYSTEM, "zh_HK"))
        assertEquals(ResolvedLanguage.JA, AppI18n.resolve(AppLanguage.SYSTEM, Locale.JAPANESE))
        assertEquals(ResolvedLanguage.JA, AppI18n.resolveLanguageTag(AppLanguage.SYSTEM, "ja-JP"))
        assertEquals(ResolvedLanguage.EN, AppI18n.resolve(AppLanguage.SYSTEM, Locale.US))
        assertEquals(ResolvedLanguage.EN, AppI18n.resolve(AppLanguage.SYSTEM, Locale.FRENCH))
        assertEquals(ResolvedLanguage.EN, AppI18n.resolveLanguageTag(AppLanguage.SYSTEM, "de-DE"))
        assertEquals(ResolvedLanguage.EN, AppI18n.resolveLanguageTag(AppLanguage.SYSTEM, ""))
    }

    @Test
    fun resolveLanguage_explicitChoiceOverridesSystemLocale() {
        for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE, Locale.JAPANESE, Locale.GERMAN)) {
            assertEquals(ResolvedLanguage.ZH, AppI18n.resolve(AppLanguage.ZH, locale))
            assertEquals(ResolvedLanguage.EN, AppI18n.resolve(AppLanguage.EN, locale))
            assertEquals(ResolvedLanguage.JA, AppI18n.resolve(AppLanguage.JA, locale))
        }
    }

    @Test
    fun settingsRepository_appLanguageDefaultsToSystemAndPersistsRoundTrip() {
        val store = InMemoryKeyValueStore()
        val repo1 = SettingsRepository(store)
        assertEquals(AppLanguage.SYSTEM, repo1.settings.value.appLanguage)

        for (lang in listOf(AppLanguage.ZH, AppLanguage.EN, AppLanguage.JA, AppLanguage.SYSTEM)) {
            repo1.setAppLanguage(lang)
            assertEquals(lang, repo1.settings.value.appLanguage)
            assertEquals(lang.storageValue, store.getString(SettingsRepository.KEY_APP_LANGUAGE, null))

            val reloaded = SettingsRepository(store)
            assertEquals(lang, reloaded.settings.value.appLanguage)
        }

        // Invalid stored key gracefully falls back to SYSTEM
        store.putString(SettingsRepository.KEY_APP_LANGUAGE, "xx-unknown")
        assertEquals(AppLanguage.SYSTEM, SettingsRepository(store).settings.value.appLanguage)
    }

    @Test
    fun allLanguagesHaveFullKeyParityNonBlankValuesAndMatchingPlaceholders() {
        val expectedKeys = Str.entries.toSet()
        val en = AppI18n.stringsFor(ResolvedLanguage.EN)
        val zh = AppI18n.stringsFor(ResolvedLanguage.ZH)
        val ja = AppI18n.stringsFor(ResolvedLanguage.JA)

        for ((langName, strings) in listOf("EN" to en, "ZH" to zh, "JA" to ja)) {
            assertEquals("$langName missing or extra keys", expectedKeys, strings.table.keys)
            for (key in Str.entries) {
                val value = strings[key]
                assertTrue("$langName[$key] must not be blank", value.isNotBlank())
            }
        }

        val placeholderRegex = Regex("""\{(\d+)\}""")
        for (key in Str.entries) {
            val enPlaceholders = placeholderRegex.findAll(en[key]).map { it.value }.toSet()
            val zhPlaceholders = placeholderRegex.findAll(zh[key]).map { it.value }.toSet()
            val jaPlaceholders = placeholderRegex.findAll(ja[key]).map { it.value }.toSet()
            assertEquals("ZH placeholder mismatch for $key", enPlaceholders, zhPlaceholders)
            assertEquals("JA placeholder mismatch for $key", enPlaceholders, jaPlaceholders)
        }
    }

    @Test
    fun stringTablesHaveZeroBilingualLeaks() {
        val cjkRegex = Regex("""[\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF]""")
        // Detect bilingual CN/JA + English word separators (e.g. "Agent Feedback · 反馈给 Agent", "标题 / Title")
        // while allowing pure uppercase technical acronyms (SAF, JSON, NAS, PC, FTP, HTTP, ZIP, QR).
        val bilingualDotOrSlash = Regex("""(?:[A-Z][a-z]{2,}|[a-z]{3,})\s+[·/]\s+[\u3040-\u30FF\u4E00-\u9FFF]|[\u3040-\u30FF\u4E00-\u9FFF]\s+[·/]\s+(?:[A-Z][a-z]{2,}|[a-z]{3,})""")
        val allowedNativeLanguageNames = setOf(Str.LANGUAGE_ZH, Str.LANGUAGE_JA)
        val brandNames = listOf("Google Drive", "Bluetooth", "Android", "iPhone", "Files", "Wi-Fi", "Google")

        val en = AppI18n.stringsFor(ResolvedLanguage.EN)
        for (key in Str.entries) {
            if (key in allowedNativeLanguageNames) continue
            assertFalse(
                "EN[$key] unexpectedly contains CJK: ${en[key]}",
                cjkRegex.containsMatchIn(en[key]),
            )
        }

        for (lang in listOf(ResolvedLanguage.EN, ResolvedLanguage.ZH, ResolvedLanguage.JA)) {
            val strings = AppI18n.stringsFor(lang)
            for (key in Str.entries) {
                // Brand names / product names stay untranslated by design (Files, Google Drive, Bluetooth, …).
                val value = brandNames.fold(strings[key]) { acc, brand -> acc.replace(brand, "#") }
                assertFalse(
                    "$lang[$key] looks bilingual (CN/JA + EN separator): $value",
                    bilingualDotOrSlash.containsMatchIn(value),
                )
            }
        }
    }

    @Test
    fun toolsScreenSourceHasZeroHardcodedBilingualAgentFeedbackStrings() {
        val toolsFile = locateAppFile("src/main/java/com/minifile/explorer/ui/screens/ToolsScreen.kt")
        val source = toolsFile.readText(Charsets.UTF_8)
        assertFalse("ToolsScreen must not contain bilingual Agent Feedback string", source.contains("反馈给 Agent"))
        assertFalse("ToolsScreen must not contain hardcoded Chinese feedback UI", source.contains("发送反馈给"))
        assertFalse("ToolsScreen must not contain hardcoded Chinese title", source.contains("问题描述"))
    }

    @Test
    fun androidXmlStringResourcesHaveFullKeyParityAcrossEnZhJa() {
        val enXml = locateAppFile("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val zhXml = locateAppFile("src/main/res/values-zh-rCN/strings.xml").readText(Charsets.UTF_8)
        val jaXml = locateAppFile("src/main/res/values-ja/strings.xml").readText(Charsets.UTF_8)

        val nameRegex = Regex("""<string\s+name="([^"]+)">([^<]+)</string>""")
        val enMap = nameRegex.findAll(enXml).associate { it.groupValues[1] to it.groupValues[2] }
        val zhMap = nameRegex.findAll(zhXml).associate { it.groupValues[1] to it.groupValues[2] }
        val jaMap = nameRegex.findAll(jaXml).associate { it.groupValues[1] to it.groupValues[2] }

        assertTrue("values/strings.xml should have notification + app strings", enMap.size >= 10)
        assertEquals("values-zh-rCN/strings.xml key parity mismatch", enMap.keys, zhMap.keys)
        assertEquals("values-ja/strings.xml key parity mismatch", enMap.keys, jaMap.keys)

        assertEquals("Files", enMap["app_name"])
        assertEquals("Files", zhMap["app_name"])
        assertEquals("Files", jaMap["app_name"])
        assertEquals("Save to Files", enMap["save_to_files"])
        assertEquals("保存到 Files", zhMap["save_to_files"])
        assertEquals("Files に保存", jaMap["save_to_files"])
    }

    @Test
    fun agentFeedbackAndDiagnosticStatusRespectSelectedLanguage() {
        val meta = FeedbackMetadata(
            versionName = "1.0.26",
            versionCode = 10026,
            deviceModel = "Pixel",
            androidRelease = "15",
            sdkInt = 35,
        )
        val zhDraft = AgentFeedbackBuilder.build(
            recipientEmail = "agent@example.com",
            feedbackText = "测试问题",
            metadata = meta,
            title = "测试标题",
            strings = AppI18n.stringsFor(ResolvedLanguage.ZH),
        )
        assertTrue("Expected Chinese description label, got:\n${zhDraft.body}", zhDraft.body.contains("问题描述:"))
        assertFalse(zhDraft.body.contains("Issue Description:"))

        val jaDraft = AgentFeedbackBuilder.build(
            recipientEmail = "agent@example.com",
            feedbackText = "テスト問題",
            metadata = meta,
            title = "テストタイトル",
            strings = AppI18n.stringsFor(ResolvedLanguage.JA),
        )
        assertTrue("Expected Japanese description label, got:\n${jaDraft.body}", jaDraft.body.contains("問題の詳細:"))
        assertFalse(jaDraft.body.contains("Issue Description:"))

        val zh = AppI18n.stringsFor(ResolvedLanguage.ZH)
        val ja = AppI18n.stringsFor(ResolvedLanguage.JA)
        assertEquals(zh[Str.DIAG_STATUS_OFF], DiagnosticExport.statusLine(false, 0L, zh))
        assertEquals(ja[Str.DIAG_STATUS_OFF], DiagnosticExport.statusLine(false, 0L, ja))
        assertTrue(DiagnosticExport.statusLine(false, 0L, zh).startsWith("已关闭"))
        assertTrue(DiagnosticExport.statusLine(false, 0L, ja).startsWith("オフ"))
    }

    @Test
    fun httpFileServerWebUiServesLocalizedHtmlAndJsForEnZhJa() {
        val rootDir = tempFolder.newFolder("web_i18n_root")
        File(rootDir, "sample.txt").writeText("hello")

        var activeLang = ResolvedLanguage.EN
        val srv = HttpFileServerEngine(
            config = HttpConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(rootDir.canonicalPath),
                readOnly = false,
            ),
            fs = LocalFileSystem(),
            languageProvider = { activeLang },
        )
        srv.start()
        try {
            fun fetchRootHtml(): String {
                val conn = URL("http://127.0.0.1:${srv.boundPort}/").openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                return try {
                    assertEquals(200, conn.responseCode)
                    conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                } finally {
                    conn.disconnect()
                }
            }

            // English
            activeLang = ResolvedLanguage.EN
            val enHtml = fetchRootHtml()
            assertTrue(enHtml.contains("<html lang=\"en\">"))
            assertTrue(enHtml.contains("+ New Folder"))
            assertTrue(enHtml.contains("Upload Files"))
            assertTrue(enHtml.contains("Download Folder (.zip)"))
            assertTrue(enHtml.contains("const I18N = {"))
            assertTrue(enHtml.contains("New folder name:"))

            // Simplified Chinese (reactive without restarting server!)
            activeLang = ResolvedLanguage.ZH
            val zhHtml = fetchRootHtml()
            assertTrue(zhHtml.contains("<html lang=\"zh-CN\">"))
            assertTrue(zhHtml.contains("+ 新建文件夹"))
            assertTrue(zhHtml.contains("上传文件"))
            assertTrue(zhHtml.contains("下载文件夹 (.zip)"))
            assertTrue(zhHtml.contains("新建文件夹名称："))
            assertFalse(zhHtml.contains("+ New Folder"))

            // Japanese (reactive without restarting server!)
            activeLang = ResolvedLanguage.JA
            val jaHtml = fetchRootHtml()
            assertTrue(jaHtml.contains("<html lang=\"ja\">"))
            assertTrue(jaHtml.contains("+ 新規フォルダ"))
            assertTrue(jaHtml.contains("ファイルをアップロード"))
            assertTrue(jaHtml.contains("フォルダをダウンロード (.zip)"))
            assertTrue(jaHtml.contains("新規フォルダ名："))
            assertFalse(jaHtml.contains("+ New Folder"))

            // Localized Web UI API error (409 conflict on /mkdir in Japanese)
            val conflictConn = URL("http://127.0.0.1:${srv.boundPort}/mkdir?path=%2F&name=sample.txt")
                .openConnection() as HttpURLConnection
            conflictConn.requestMethod = "POST"
            conflictConn.connectTimeout = 5_000
            conflictConn.readTimeout = 5_000
            try {
                assertEquals(409, conflictConn.responseCode)
                val errBody = conflictConn.errorStream.bufferedReader(Charsets.UTF_8).readText()
                assertTrue("Expected Japanese conflict message, got: $errBody", errBody.contains("すでに存在します"))
                assertFalse("Should not leak English conflict message: $errBody", errBody.contains("already exists"))
            } finally {
                conflictConn.disconnect()
            }
        } finally {
            srv.stop()
        }
    }

    @Test
    fun peerDiscoveryValidationAndInterceptionErrorsFollowActiveLanguage() {
        fun parseError(raw: String): String = try {
            PeerDiscoveryEngine.parseHostAndPort(raw)
            error("expected PeerTransferException for '$raw'")
        } catch (e: PeerTransferException) {
            e.userMessage
        }

        AppI18n.setResolved(AppLanguage.ZH, ResolvedLanguage.ZH)
        assertEquals("请输入设备 IP 地址", parseError("  "))
        assertEquals("端口必须在 1 到 65535 之间", parseError("192.168.1.5:70000"))
        val zhIntercept = PeerTransferException.interceptedMessage("iPhone", 302)
        assertTrue(zhIntercept, zhIntercept.startsWith("与 iPhone 的连接被拦截 (HTTP 302)"))

        AppI18n.setResolved(AppLanguage.JA, ResolvedLanguage.JA)
        assertEquals("デバイスの IP アドレスを入力してください", parseError("  "))
        val jaIntercept = PeerTransferException.interceptedMessage("iPhone", 302)
        assertTrue(jaIntercept, jaIntercept.startsWith("iPhone への接続が遮断されました (HTTP 302)"))

        AppI18n.setResolved(AppLanguage.EN, ResolvedLanguage.EN)
        assertEquals("Enter a device IP address", parseError("  "))
    }

    @Test
    fun bleLinkPhasesAndDriveErrorsAreLocalizedAndAuthExpiryIsLanguageIndependent() {
        val zh = AppI18n.stringsFor(ResolvedLanguage.ZH)
        val ja = AppI18n.stringsFor(ResolvedLanguage.JA)
        assertEquals("正在通过蓝牙接收…（1.0 MB / 2.0 MB）", zh.format(Str.BLE_RECEIVING_VIA_BT_FMT, "1.0 MB / 2.0 MB"))
        assertEquals("Pixel の承認を待っています…", ja.format(Str.BLE_WAITING_ACCEPT_FMT, "Pixel"))
        assertEquals("无法连接到 Pixel 上的 Files — 请在 Pixel 上保持 Files 打开并开启蓝牙", zh.format(Str.BLE_COULD_NOT_REACH_FMT, "Pixel"))

        // Re-auth detection must work whatever language the error was produced in.
        for (lang in ResolvedLanguage.entries) {
            val strings = AppI18n.stringsFor(lang)
            assertTrue(AppI18n.isSignInExpiredMessage(strings[Str.GDRIVE_SIGNIN_EXPIRED_AGAIN]))
            assertTrue(AppI18n.isSignInExpiredMessage(strings[Str.GOOGLE_SIGNIN_EXPIRED_AGAIN]))
            assertTrue(AppI18n.isSignInExpiredMessage("x.txt: " + strings[Str.GDRIVE_SIGNIN_EXPIRED]))
            assertFalse(AppI18n.isSignInExpiredMessage(strings[Str.GDRIVE_STORAGE_FULL]))
        }
        assertFalse(AppI18n.isSignInExpiredMessage(null))
        assertEquals("Google Drive 登录已过期 — 请重新登录", zh[Str.GDRIVE_SIGNIN_EXPIRED_AGAIN])
        assertEquals("Google Drive の保存容量がいっぱいです", ja[Str.GDRIVE_STORAGE_FULL])

        // Source guards: user-facing BLE / Drive copy goes through Str keys, not English literals.
        val ble = locateAppFile("src/main/java/com/minifile/explorer/service/BlePeerCoordinator.kt").readText()
        for (literal in listOf("\"Transfer complete\"", "\"Bluetooth link lost\"", "\"Timed out\"", "Receiving via Bluetooth…", "\"Nearby device\"")) {
            assertFalse("BlePeerCoordinator still has $literal", ble.contains(literal))
        }
        assertFalse("receiver timeout must not depend on English phase text", ble.contains("startsWith(\"Receiving\")"))
        for (rel in listOf(
            "src/main/java/com/minifile/explorer/core/gdrive/DriveHttp.kt",
            "src/main/java/com/minifile/explorer/core/gdrive/GoogleDriveAuth.kt",
            "src/main/java/com/minifile/explorer/data/gdrive/AndroidAccountTokenProvider.kt",
        )) {
            val src = locateAppFile(rel).readText()
            assertFalse("$rel still has English sign-in copy", src.contains("message = \"Google"))
        }
        val vm = locateAppFile("src/main/java/com/minifile/explorer/ui/MainViewModel.kt").readText()
        assertFalse(vm.contains("contains(\"sign-in expired\""))
    }

    @Test
    fun linkBlockersDeclinesHotspotAndJoinErrorsFollowActiveLanguage() {
        AppI18n.setResolved(AppLanguage.JA, ResolvedLanguage.JA)
        assertEquals(
            "iPhone 同士では近距離共有 (AWDL) を使用してください",
            HostDecision.blockerMessage(LinkBlocker.IOS_PAIR, "x"),
        )
        assertTrue(HostDecision.blockerMessage(LinkBlocker.SENDER_CANNOT_HOST, "Pixel 9").contains("Pixel 9"))
        val ja = AppI18n.current()
        assertEquals("Google ログインが拒否されました", ja[Str.GDRIVE_SIGNIN_DENIED])
        assertEquals("Google Drive のトークンが無効か期限切れです", ja[Str.GDRIVE_TOKEN_INVALID])
        val deniedPage = GoogleOAuthLoopback.resultHtml(success = false)
        assertTrue(deniedPage.contains("<html lang=\"ja\">"))
        assertTrue(deniedPage.contains("<h2>Google ログインが拒否されました。</h2>"))
        assertFalse(deniedPage.contains("return to the Files app"))

        AppI18n.setResolved(AppLanguage.ZH, ResolvedLanguage.ZH)
        assertEquals("两台 iPhone 之间请使用附近共享 (AWDL)", HostDecision.blockerMessage(LinkBlocker.IOS_PAIR, "x"))
        assertEquals(
            "无法与 Pixel 建立蓝牙通道 — 请在 Pixel 上保持 Files 打开后重试",
            AppI18n.current().format(Str.BLE_CHANNEL_FAILED_FMT, "Pixel"),
        )

        AppI18n.setResolved(AppLanguage.EN, ResolvedLanguage.EN)
        assertEquals("Use Nearby (AWDL) between two iPhones", HostDecision.blockerMessage(LinkBlocker.IOS_PAIR, "x"))
        assertEquals("Declined by Pixel", AppI18n.current().format(Str.PEER_ERR_DECLINED_FMT, "Pixel"))

        // Source guards: these user-facing literals must go through Str keys.
        val guards = mapOf(
            "src/main/java/com/minifile/explorer/service/BlePeerCoordinator.kt" to listOf(
                "\"Declined by", "Could not open a Bluetooth channel", "Use Nearby (AWDL)", "out of Bluetooth range",
                "did not respond\"", "did not accept in time", "\"\$ackName could not start a hotspot", "\"Scan this QR",
            ),
            "src/main/java/com/minifile/explorer/core/peer/HotspotLinkModels.kt" to listOf("\"Allow Nearby Wi-Fi", "\"Use Nearby"),
            "src/main/java/com/minifile/explorer/core/gdrive/GoogleDriveAuth.kt" to listOf("token is invalid or expired"),
            "src/main/java/com/minifile/explorer/core/gdrive/GoogleOAuthLoopback.kt" to listOf("sign-in was denied"),
            "src/main/java/com/minifile/explorer/core/peer/PeerDiscoveryEngine.kt" to listOf("is not a Files peer\"", "needs newer Files\""),
            "src/main/java/com/minifile/explorer/service/LocalOnlyHotspotManager.kt" to listOf("HotspotException(\"", "Failed(\""),
            "src/main/java/com/minifile/explorer/service/WifiSpecifierConnector.kt" to listOf("JoinException(\""),
        )
        for ((rel, literals) in guards) {
            val src = locateAppFile(rel).readText()
            for (literal in literals) assertFalse("$rel still has $literal", src.contains(literal))
        }
    }

    private fun locateAppFile(relativeToApp: String): File {
        val candidates = listOf(
            File(relativeToApp),
            File("app", relativeToApp),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $relativeToApp from ${File(".").absolutePath}")
    }
}
