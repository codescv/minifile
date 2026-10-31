package com.minifile.explorer.core.gdrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Guards the repository against accidentally committing Google OAuth client secrets,
 * live OAuth2 access tokens, or Google Cloud API keys that trigger GitHub Push Protection (§2.5, B4),
 * and enforces the pure-JVM rule (zero `android.*` / `androidx.*` / `dalvik.*` imports under `core/`).
 */
class SecretScanTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    companion object {
        private val SKIPPED_DIR_NAMES = setOf(
            ".git",
            "build",
            ".gradle",
            ".worktrees",
            "__pycache__",
            ".kotlin",
            ".idea",
        )

        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "ico",
            "jar", "apk", "aab", "dex", "class", "so",
            "keystore", "jks", "p12", "zip", "gz", "pdf", "ttf", "otf", "woff", "woff2",
        )

        // Assembled via concatenation so this source file never matches its own patterns.
        private val SECRET_PATTERNS: List<Pair<String, Regex>> = listOf(
            "Google OAuth client secret" to Regex("GOC" + "SPX-" + "[A-Za-z0-9_-]{20,}"),
            "Google OAuth access token" to Regex("ya" + "29\\." + "[A-Za-z0-9_-]{20,}"),
            "Google Cloud API key" to Regex("AI" + "za" + "[0-9A-Za-z_-]{35}"),
        )

        private val FORBIDDEN_CORE_IMPORT = Regex("""^\s*import\s+(android|androidx|dalvik)\.""")

        fun scanTreeForSecrets(root: File): List<String> {
            val hits = ArrayList<String>()
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)

            root.walkTopDown()
                .onEnter { dir -> dir == root || dir.name !in SKIPPED_DIR_NAMES }
                .filter { it.isFile }
                .forEach { file ->
                    val ext = file.extension.lowercase()
                    if (ext in BINARY_EXTENSIONS) return@forEach
                    if (file.length() > 4L * 1024L * 1024L) return@forEach

                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                    val probeLen = minOf(bytes.size, 4096)
                    for (i in 0 until probeLen) {
                        if (bytes[i] == 0.toByte()) return@forEach
                    }

                    val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
                    val relPath = file.relativeTo(root).path
                    text.lineSequence().forEachIndexed { idx, line ->
                        for ((label, regex) in SECRET_PATTERNS) {
                            if (regex.containsMatchIn(line)) {
                                hits.add("$relPath:${idx + 1}: matched $label")
                            }
                        }
                    }
                }
            return hits
        }

        fun locateRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory) {
                    return dir
                }
                dir = dir.parentFile
            }
            error("Could not locate repository root containing settings.gradle.kts")
        }
    }

    @Test
    fun repositoryContainsZeroGoogleSecretPatterns() {
        val repoRoot = locateRepoRoot()
        val violations = scanTreeForSecrets(repoRoot)
        assertTrue(
            "Found forbidden Google secret patterns in repository:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun scannerDetectsSyntheticClientSecretAccessTokenAndApiKey() {
        val root = tempFolder.newFolder("synthetic_repo")
        val fakeClientSecret = "GOC" + "SPX-" + "AbCdEfGhIjKlMnOpQrStUvWx"
        val fakeAccessToken = "ya" + "29." + "a0AfH6SMBx1234567890abcdef"
        val fakeApiKey = "AI" + "za" + "1234567890abcdefghijklmnopqrstuvwxyz1"

        File(root, "leak1.txt").writeText("client_secret = $fakeClientSecret\n")
        File(root, "leak2.json").writeText("{\"access_token\":\"$fakeAccessToken\"}\n")
        File(root, "leak3.md").writeText("key=$fakeApiKey\n")
        val ignoredBuild = File(root, "build").apply { mkdirs() }
        File(ignoredBuild, "ignored.txt").writeText(fakeClientSecret)

        val hits = scanTreeForSecrets(root)
        assertEquals("Expected 3 violations, got: $hits", 3, hits.size)
    }

    @Test
    fun corePackageHasZeroAndroidOrDalvikImports() {
        val repoRoot = locateRepoRoot()
        val coreRoot = File(repoRoot, "app/src/main/java/com/minifile/explorer/core")
        assertTrue("Expected core package directory at ${coreRoot.path}", coreRoot.isDirectory)

        val violations = ArrayList<String>()
        coreRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { ktFile ->
                val rel = ktFile.relativeTo(repoRoot).path
                ktFile.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (FORBIDDEN_CORE_IMPORT.containsMatchIn(line)) {
                            violations.add("$rel:${idx + 1}: $line")
                        }
                    }
                }
            }
        assertTrue(
            "Found forbidden Android/Dalvik imports under core/:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
