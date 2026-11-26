package com.minifile.explorer.core.feedback

import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.i18n.AppStrings
import com.minifile.explorer.core.i18n.ResolvedLanguage
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.update.SemanticVersion
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class FeedbackMetadata(
    val appName: String = "Files",
    val packageName: String = "com.minifile.explorer",
    val versionName: String,
    val versionCode: Int,
    val deviceManufacturer: String = "",
    val deviceModel: String = "",
    val androidRelease: String = "",
    val sdkInt: Int = 0,
    val extraContext: Map<String, String> = emptyMap(),
)

data class BuiltAgentFeedback(
    val recipientEmail: String,
    val subject: String,
    val body: String,
    val mailtoUri: String,
)

/**
 * Pure-JVM builder for Agent Feedback emails (`minifile: [vX.Y.Z] <summary>`).
 * Keeps zero Android framework dependencies so subject, body, validation, and RFC 6068 `mailto:`
 * URI construction are deterministically unit-testable on the JVM.
 */
object AgentFeedbackBuilder {

    const val PROJECT_PREFIX = "minifile"
    const val EMAIL_PLACEHOLDER = "agent@example.com"
    const val DEFAULT_SUMMARY = "App feedback"
    const val SCREENSHOT_SUMMARY = "Screenshot feedback"
    const val IMAGE_ATTACHMENT_SUMMARY = "Image attachment"
    const val TITLE_LABEL = "Title"
    const val DESCRIPTION_LABEL = "Issue Description"
    const val MAX_SUMMARY_LENGTH = 80

    private const val MAX_EMAIL_LENGTH = 254
    private const val MAX_LOCAL_PART_LENGTH = 64
    private const val MAX_DOMAIN_LENGTH = 253
    private const val MAX_DOMAIN_LABEL_LENGTH = 63

    private val ALLOWED_LOCAL_SPECIALS = setOf(
        '.', '!', '#', '$', '%', '&', '\'', '*', '+', '-', '/', '=', '?', '^', '_', '`', '{', '|', '}', '~',
    )

    fun normalizeEmail(raw: String?): String = raw?.trim().orEmpty()

    fun isValidEmail(raw: String?): Boolean {
        val email = normalizeEmail(raw)
        if (email.isEmpty() || email.length > MAX_EMAIL_LENGTH) return false
        for (ch in email) {
            if (ch.code <= 0x20 || ch.code >= 0x7F) return false
            if (ch == ',' || ch == ';' || ch == '<' || ch == '>' || ch == '"' || ch == '\\') {
                return false
            }
        }

        val atIndex = email.indexOf('@')
        if (atIndex <= 0 || atIndex != email.lastIndexOf('@') || atIndex == email.lastIndex) {
            return false
        }

        val local = email.substring(0, atIndex)
        val domain = email.substring(atIndex + 1)
        if (local.length > MAX_LOCAL_PART_LENGTH || domain.length < 3 || domain.length > MAX_DOMAIN_LENGTH) {
            return false
        }
        if (local.startsWith('.') || local.endsWith('.') || local.contains("..")) {
            return false
        }
        for (ch in local) {
            val isAsciiAlnum = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
            if (!isAsciiAlnum && ch !in ALLOWED_LOCAL_SPECIALS) {
                return false
            }
        }

        val labels = domain.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > MAX_DOMAIN_LABEL_LENGTH) return false
            if (!isAsciiAlnum(label.first()) || !isAsciiAlnum(label.last())) return false
            for (ch in label) {
                if (!isAsciiAlnum(ch) && ch != '-') return false
            }
        }
        val tld = labels.last()
        if (tld.length < 2 || !tld.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            return false
        }
        return true
    }

    fun normalizeVersionTag(versionName: String?): String {
        val stripped = SemanticVersion.stripDebugSuffix(versionName)
            ?.trim()
            .orEmpty()
            .removePrefix("v")
            .removePrefix("V")
            .trim()
        val clean = stripped.ifEmpty { "0.0.0" }
        return "v$clean"
    }

    fun extractSummary(
        rawSummary: String?,
        fallbackBody: String = "",
        hasScreenshot: Boolean = false,
        maxLength: Int = MAX_SUMMARY_LENGTH,
        screenshotSummary: String = SCREENSHOT_SUMMARY,
        strings: AppStrings = AppI18n.stringsFor(ResolvedLanguage.EN),
    ): String {
        val explicit = sanitizeSingleLine(rawSummary)
        val candidate = if (explicit.isNotEmpty()) {
            explicit
        } else {
            val firstNonBlankLine = fallbackBody
                .lineSequence()
                .map { sanitizeSingleLine(it) }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            when {
                firstNonBlankLine.isNotEmpty() -> firstNonBlankLine
                hasScreenshot -> screenshotSummary.ifBlank { strings[Str.AGENT_FEEDBACK_SCREENSHOT_SUMMARY] }
                else -> strings[Str.AGENT_FEEDBACK_DEFAULT_SUMMARY]
            }
        }
        val effectiveMax = maxLength.coerceAtLeast(8)
        return if (candidate.length <= effectiveMax) {
            candidate
        } else {
            candidate.substring(0, effectiveMax - 1).trimEnd() + "…"
        }
    }

    fun buildSubject(
        versionName: String,
        summary: String?,
        fallbackBody: String = "",
        hasScreenshot: Boolean = false,
        screenshotSummary: String = SCREENSHOT_SUMMARY,
        strings: AppStrings = AppI18n.stringsFor(ResolvedLanguage.EN),
    ): String {
        val tag = normalizeVersionTag(versionName)
        val effectiveScreenshotSummary = if (screenshotSummary == SCREENSHOT_SUMMARY) {
            strings[Str.AGENT_FEEDBACK_SCREENSHOT_SUMMARY]
        } else {
            screenshotSummary
        }
        val cleanSummary = extractSummary(
            rawSummary = summary,
            fallbackBody = fallbackBody,
            hasScreenshot = hasScreenshot,
            screenshotSummary = effectiveScreenshotSummary,
            strings = strings,
        )
        return "$PROJECT_PREFIX: [$tag] $cleanSummary"
    }

    fun buildBody(
        feedbackText: String,
        metadata: FeedbackMetadata,
        screenshotName: String? = null,
        title: String? = null,
        strings: AppStrings = AppI18n.stringsFor(ResolvedLanguage.EN),
    ): String {
        val cleanTitle = sanitizeSingleLine(title)
        val normalizedMessage = feedbackText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val cleanScreenshot = sanitizeSingleLine(screenshotName)
        val messageSection = when {
            normalizedMessage.isNotEmpty() -> normalizedMessage
            cleanScreenshot.isNotEmpty() -> strings.format(Str.AGENT_FEEDBACK_SEE_ATTACHED, cleanScreenshot)
            else -> strings[Str.AGENT_FEEDBACK_NO_DESC]
        }

        val deviceLabel = listOf(metadata.deviceManufacturer.trim(), metadata.deviceModel.trim())
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")

        return buildString {
            if (cleanTitle.isNotEmpty()) {
                append(strings[Str.AGENT_FEEDBACK_TITLE_LABEL]).append(": ").append(cleanTitle).append("\n\n")
                append(strings[Str.AGENT_FEEDBACK_DESC_LABEL]).append(":\n")
            }
            append(messageSection)
            append("\n\n---\n")
            append("App: ").append(metadata.appName.ifBlank { "Files" })
            if (metadata.packageName.isNotBlank()) {
                append(" (").append(metadata.packageName.trim()).append(')')
            }
            append('\n')
            append("Version: ")
                .append(metadata.versionName.trim().ifEmpty { "unknown" })
                .append(" (versionCode ")
                .append(metadata.versionCode)
                .append(")\n")
            if (deviceLabel.isNotEmpty()) {
                append("Device: ").append(deviceLabel).append('\n')
            }
            if (metadata.androidRelease.isNotBlank() || metadata.sdkInt > 0) {
                append("Android: ")
                if (metadata.androidRelease.isNotBlank()) {
                    append(metadata.androidRelease.trim())
                    if (metadata.sdkInt > 0) {
                        append(" (SDK ").append(metadata.sdkInt).append(')')
                    }
                } else {
                    append("SDK ").append(metadata.sdkInt)
                }
                append('\n')
            }
            if (cleanScreenshot.isNotEmpty()) {
                append("Attachment: ").append(cleanScreenshot).append('\n')
            }
            for ((key, value) in metadata.extraContext) {
                val k = sanitizeSingleLine(key)
                val v = sanitizeSingleLine(value)
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    append(k).append(": ").append(v).append('\n')
                }
            }
        }.trimEnd()
    }

    fun buildMailtoUri(
        recipientEmail: String,
        subject: String,
        body: String,
        strings: AppStrings = AppI18n.stringsFor(ResolvedLanguage.EN),
    ): String {
        val normalized = normalizeEmail(recipientEmail)
        require(isValidEmail(normalized)) { strings[Str.AGENT_FEEDBACK_INVALID_EMAIL_ERR] }
        val encodedRecipient = encodeMailtoRecipient(normalized)
        val encodedSubject = encodeMailtoComponent(subject)
        val encodedBody = encodeMailtoComponent(body)
        return "mailto:$encodedRecipient?subject=$encodedSubject&body=$encodedBody"
    }

    fun build(
        recipientEmail: String,
        feedbackText: String,
        metadata: FeedbackMetadata,
        summary: String? = null,
        screenshotName: String? = null,
        title: String = "",
        strings: AppStrings = AppI18n.stringsFor(ResolvedLanguage.EN),
    ): BuiltAgentFeedback {
        val normalizedEmail = normalizeEmail(recipientEmail)
        require(isValidEmail(normalizedEmail)) { strings[Str.AGENT_FEEDBACK_INVALID_EMAIL_ERR] }
        val hasScreenshot = !screenshotName.isNullOrBlank()
        val hasTitle = title.isNotBlank()
        require(feedbackText.isNotBlank() || !summary.isNullOrBlank() || hasTitle || hasScreenshot) {
            strings[Str.AGENT_FEEDBACK_EMPTY_ERR]
        }
        // Subject summary priority: explicit title > legacy summary > first non-empty line of
        // feedbackText > localized image attachment label (image-only feedback) > default summary.
        val builtSubject = buildSubject(
            versionName = metadata.versionName,
            summary = if (hasTitle) title else summary,
            fallbackBody = feedbackText,
            hasScreenshot = hasScreenshot,
            screenshotSummary = strings[Str.AGENT_FEEDBACK_IMAGE_SUMMARY],
            strings = strings,
        )
        val builtBody = buildBody(
            feedbackText = feedbackText,
            metadata = metadata,
            screenshotName = screenshotName,
            title = title,
            strings = strings,
        )
        val uri = buildMailtoUri(
            recipientEmail = normalizedEmail,
            subject = builtSubject,
            body = builtBody,
            strings = strings,
        )
        return BuiltAgentFeedback(
            recipientEmail = normalizedEmail,
            subject = builtSubject,
            body = builtBody,
            mailtoUri = uri,
        )
    }

    internal fun encodeMailtoComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun encodeMailtoRecipient(email: String): String {
        val at = email.indexOf('@')
        val local = email.substring(0, at)
        val domain = email.substring(at + 1)
        val sb = StringBuilder(email.length + 8)
        for (b in local.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt().and(0xFF).toChar()
            val safe = isAsciiAlnum(c) || c == '.' || c == '_' || c == '-' || c == '+'
            if (safe) {
                sb.append(c)
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[b.toInt().ushr(4).and(0xF)])
                sb.append("0123456789ABCDEF"[b.toInt().and(0xF)])
            }
        }
        sb.append('@').append(domain)
        return sb.toString()
    }

    private fun sanitizeSingleLine(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(raw.length)
        var prevSpace = true
        for (ch in raw) {
            val isControlOrSpace = ch.isWhitespace() || ch.code < 0x20 || ch.code == 0x7F
            if (isControlOrSpace) {
                if (!prevSpace) {
                    sb.append(' ')
                    prevSpace = true
                }
            } else {
                sb.append(ch)
                prevSpace = false
            }
        }
        return sb.toString().trim()
    }

    private fun isAsciiAlnum(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
}
