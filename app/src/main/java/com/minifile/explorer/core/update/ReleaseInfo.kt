package com.minifile.explorer.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL
import java.util.Locale

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = -1L,
    @SerialName("content_type") val contentType: String? = null,
)

@Serializable
data class ReleaseJson(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    val cleanVersion: String
        get() = tagName.trim().removePrefix("v").removePrefix("V")
}

data class UpdateCheckResult(
    val currentVersion: String,
    val latest: ReleaseJson,
    val apk: ReleaseAsset?,
    val updateAvailable: Boolean,
    /** Lower-case hex SHA-256 of [apk] from the release's `SHA256SUMS.txt`, or `null` if not attached. */
    val expectedSha256: String? = null,
)

/**
 * Parses `sha256sum`-style `SHA256SUMS.txt` manifests (`<64 hex>  [*]<file>`) published by
 * `.github/workflows/release.yml` into `lower-case basename -> lower-case hex digest`.
 */
object Sha256SumsParser {
    private val HEX_64 = Regex("""^[0-9a-fA-F]{64}$""")
    private val LINE_SPLIT = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")

    fun parse(rawText: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (rawLine in rawText.removePrefix("\uFEFF").lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val m = LINE_SPLIT.matchEntire(trimmed) ?: continue
            val hash = m.groupValues[1].lowercase(Locale.ROOT)
            val filePart = m.groupValues[2].trim().replace('\\', '/').substringAfterLast('/')
            if (filePart.isNotEmpty()) {
                out[filePart.lowercase(Locale.ROOT)] = hash
            }
        }
        return out
    }

    fun isValidHex64(s: String?): Boolean = s != null && HEX_64.matches(s.trim())
}

object ReleaseParser {
    const val DEFAULT_RELEASE_HTML_URL = "https://github.com/codescv/minifile/releases"
    const val ALLOWED_ASSET_PREFIX = "https://github.com/codescv/minifile/releases/download/"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val MINIFILE_RELEASE_APK = Regex("^minifile-.*-release\\.apk$", RegexOption.IGNORE_CASE)
    private val MINIFILE_COMPANION_ZIP = Regex("^minifile-companion-.*\\.zip$", RegexOption.IGNORE_CASE)

    fun parseLatest(rawJson: String): ReleaseJson {
        val parsed = try {
            json.decodeFromString<ReleaseJson>(rawJson)
        } catch (e: Exception) {
            throw UpdateException(UpdateErrorCode.BAD_JSON, "Invalid GitHub release JSON", e)
        }
        if (parsed.tagName.isBlank()) {
            throw UpdateException(UpdateErrorCode.BAD_JSON, "Release JSON missing tag_name")
        }
        if (parsed.draft) {
            throw UpdateException(UpdateErrorCode.NOT_FOUND, "Latest release is a draft")
        }
        return parsed
    }

    fun parseList(rawJson: String, includePre: Boolean = false): ReleaseJson? {
        val list = try {
            json.decodeFromString<List<ReleaseJson>>(rawJson)
        } catch (e: Exception) {
            throw UpdateException(UpdateErrorCode.BAD_JSON, "Invalid GitHub releases array JSON", e)
        }
        val nonDraft = list.filter { !it.draft && it.tagName.isNotBlank() }
        return if (includePre) {
            nonDraft.firstOrNull()
        } else {
            nonDraft.firstOrNull { !it.prerelease }
        }
    }

    fun pickApk(r: ReleaseJson, allowInsecureLoopback: Boolean = false): ReleaseAsset? {
        val allowedApks = r.assets.filter { asset ->
            isAllowedAssetUrl(asset.url, allowInsecureLoopback) &&
                asset.name.endsWith(".apk", ignoreCase = true)
        }
        return allowedApks.firstOrNull { MINIFILE_RELEASE_APK.matches(it.name) }
            ?: allowedApks.firstOrNull()
    }

    fun pickCompanionZip(r: ReleaseJson, allowInsecureLoopback: Boolean = false): ReleaseAsset? {
        val allowedZips = r.assets.filter { asset ->
            isAllowedAssetUrl(asset.url, allowInsecureLoopback)
        }
        return allowedZips.firstOrNull { MINIFILE_COMPANION_ZIP.matches(it.name) }
    }

    fun pickChecksums(r: ReleaseJson, allowInsecureLoopback: Boolean = false): ReleaseAsset? {
        return r.assets.firstOrNull { asset ->
            isAllowedAssetUrl(asset.url, allowInsecureLoopback) &&
                asset.name.equals("SHA256SUMS.txt", ignoreCase = true)
        }
    }

    internal fun isAllowedAssetUrl(rawUrl: String, allowInsecureLoopback: Boolean): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith(ALLOWED_ASSET_PREFIX, ignoreCase = true)) {
            return true
        }
        if (allowInsecureLoopback) {
            @Suppress("DEPRECATION")
            val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return false
            val host = parsed.host?.lowercase() ?: return false
            val proto = parsed.protocol?.lowercase() ?: return false
            return (proto == "http" || proto == "https") && (host == "127.0.0.1" || host == "localhost")
        }
        return false
    }
}
