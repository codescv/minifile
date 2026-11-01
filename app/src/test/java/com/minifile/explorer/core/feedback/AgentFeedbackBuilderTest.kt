package com.minifile.explorer.core.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class AgentFeedbackBuilderTest {

    private val sampleMetadata = FeedbackMetadata(
        appName = "Files",
        packageName = "com.minifile.explorer",
        versionName = "1.0.12",
        versionCode = 10012,
        deviceManufacturer = "Google",
        deviceModel = "Pixel 9 Pro",
        androidRelease = "15",
        sdkInt = 35,
    )

    @Test
    fun isValidEmail_acceptsStandardAndPlusTaggedAddresses() {
        assertTrue(AgentFeedbackBuilder.isValidEmail("agent@example.com"))
        assertTrue(AgentFeedbackBuilder.isValidEmail("  agent+minifile@sub.example.org  "))
        assertTrue(AgentFeedbackBuilder.isValidEmail("first.last_42@dev-mail.example.io"))
    }

    @Test
    fun isValidEmail_rejectsBlankMalformedAndHeaderInjectionInputs() {
        val invalid = listOf(
            null,
            "",
            "   ",
            "not-an-email",
            "@example.com",
            "agent@",
            "agent@localhost",
            "agent@@example.com",
            ".leading@example.com",
            "trailing.@example.com",
            "double..dot@example.com",
            "agent@-hyphen.com",
            "agent@hyphen-.com",
            "agent@example.12",
            "agent@example.c",
            "space inside@example.com",
            "agent@example.com,second@example.com",
            "agent@example.com;second@example.com",
            "<agent@example.com>",
            "agent@example.com\nBcc:evil@example.com",
            "a".repeat(65) + "@example.com",
        )
        for (candidate in invalid) {
            assertFalse("Expected invalid email for: $candidate", AgentFeedbackBuilder.isValidEmail(candidate))
        }
    }

    @Test
    fun buildSubject_formatsMinifilePrefixAndStripsDebugSuffix() {
        assertEquals(
            "minifile: [v1.0.12] Add WebDAV bookmark option",
            AgentFeedbackBuilder.buildSubject("1.0.12", "Add WebDAV bookmark option"),
        )
        assertEquals(
            "minifile: [v1.0.12] Fix PDF zoom jitter",
            AgentFeedbackBuilder.buildSubject("1.0.12-DEBUG", "Fix PDF zoom jitter"),
        )
        assertEquals(
            "minifile: [v1.0.12] Already prefixed tag",
            AgentFeedbackBuilder.buildSubject("v1.0.12", "Already prefixed tag"),
        )
    }

    @Test
    fun buildSubject_derivesFirstNonBlankLineFromBodyAndTruncatesLongSummary() {
        val multiLine = "\n  \n  深色模式下   PDF\t翻页闪烁  \n复现步骤：打开 50 页文档后快速滑动。"
        assertEquals(
            "minifile: [v1.0.12] 深色模式下 PDF 翻页闪烁",
            AgentFeedbackBuilder.buildSubject(
                versionName = "1.0.12",
                summary = null,
                fallbackBody = multiLine,
            ),
        )

        assertEquals(
            "minifile: [v1.0.12] Screenshot feedback",
            AgentFeedbackBuilder.buildSubject(
                versionName = "1.0.12",
                summary = "",
                fallbackBody = "   ",
                hasScreenshot = true,
            ),
        )

        val veryLong = "x".repeat(120)
        val subject = AgentFeedbackBuilder.buildSubject("1.0.12", veryLong)
        val prefix = "minifile: [v1.0.12] "
        assertTrue(subject.startsWith(prefix))
        val extracted = subject.removePrefix(prefix)
        assertEquals(AgentFeedbackBuilder.MAX_SUMMARY_LENGTH, extracted.length)
        assertTrue(extracted.endsWith("…"))
    }

    @Test
    fun build_constructsValidRfc6068MailtoUriAndIncludesDeviceAndVersionMetadata() {
        val built = AgentFeedbackBuilder.build(
            recipientEmail = "  agent+vibe@example.com ",
            feedbackText = "FTP 传输速率优化建议\n局域网传大文件希望显示平均速率 & 剩余时间? 100% 期待+",
            metadata = sampleMetadata.copy(
                extraContext = mapOf("Tab" to "Transfer"),
            ),
            screenshotName = "shot_01.png",
        )

        assertEquals("agent+vibe@example.com", built.recipientEmail)
        assertEquals("minifile: [v1.0.12] FTP 传输速率优化建议", built.subject)
        assertTrue(built.body.contains("App: Files (com.minifile.explorer)"))
        assertTrue(built.body.contains("Version: 1.0.12 (versionCode 10012)"))
        assertTrue(built.body.contains("Device: Google Pixel 9 Pro"))
        assertTrue(built.body.contains("Android: 15 (SDK 35)"))
        assertTrue(built.body.contains("Attachment: shot_01.png"))
        assertTrue(built.body.contains("Tab: Transfer"))

        // Verify URI parses strictly under RFC 3986 and encodes spaces as %20 rather than '+'
        val parsed = URI(built.mailtoUri)
        assertEquals("mailto", parsed.scheme)
        val rawSchemeSpecific = parsed.rawSchemeSpecificPart
        assertFalse(
            "Spaces in mailto query must be encoded as %20, not '+'",
            rawSchemeSpecific.substringAfter('?').contains('+'),
        )
        assertTrue(rawSchemeSpecific.startsWith("agent+vibe@example.com?subject="))

        val queryPairs = rawSchemeSpecific.substringAfter('?')
            .split('&')
            .associate { part ->
                val k = part.substringBefore('=')
                val v = URLDecoder.decode(part.substringAfter('='), StandardCharsets.UTF_8.name())
                k to v
            }
        assertEquals(built.subject, queryPairs["subject"])
        assertEquals(built.body, queryPairs["body"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun build_throwsWhenEmailInvalid() {
        AgentFeedbackBuilder.build(
            recipientEmail = "invalid-email",
            feedbackText = "Some feedback",
            metadata = sampleMetadata,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun build_throwsWhenBothTextAndScreenshotMissing() {
        AgentFeedbackBuilder.build(
            recipientEmail = "agent@example.com",
            feedbackText = "   ",
            metadata = sampleMetadata,
            summary = "  ",
            screenshotName = null,
        )
    }

    @Test
    fun build_withTitleDescriptionAndScreenshot_usesTitleAsSubjectAndIncludesBothInBody() {
        val built = AgentFeedbackBuilder.build(
            recipientEmail = "agent@example.com",
            feedbackText = "打开 50 页 PDF 后快速滑动\n页面出现闪烁",
            metadata = sampleMetadata,
            screenshotName = "pdf_flicker.png",
            title = "  PDF   翻页闪烁  ",
        )

        assertEquals("minifile: [v1.0.12] PDF 翻页闪烁", built.subject)
        assertTrue(built.body.startsWith("Title: PDF 翻页闪烁\n\nIssue Description:\n打开 50 页 PDF 后快速滑动\n页面出现闪烁"))
        assertTrue(built.body.contains("Attachment: pdf_flicker.png"))
        assertTrue(built.body.contains("Version: 1.0.12 (versionCode 10012)"))
        assertTrue(URI(built.mailtoUri).rawSchemeSpecificPart.startsWith("agent@example.com?subject="))
    }

    @Test
    fun build_titleOnly_isAcceptedAndBodyNotesMissingDescription() {
        val built = AgentFeedbackBuilder.build(
            recipientEmail = "agent@example.com",
            feedbackText = "",
            metadata = sampleMetadata,
            title = "支持 WebDAV 书签",
        )
        assertEquals("minifile: [v1.0.12] 支持 WebDAV 书签", built.subject)
        assertTrue(built.body.startsWith("Title: 支持 WebDAV 书签\n\nIssue Description:\n(No description provided)"))
    }

    @Test
    fun build_blankTitle_fallsBackToFirstLineOrImageAttachment() {
        val fromText = AgentFeedbackBuilder.build(
            recipientEmail = "agent@example.com",
            feedbackText = "\n  首行摘要  \n第二行",
            metadata = sampleMetadata,
            title = "   ",
        )
        assertEquals("minifile: [v1.0.12] 首行摘要", fromText.subject)
        assertFalse(fromText.body.contains("Title:"))

        val imageOnly = AgentFeedbackBuilder.build(
            recipientEmail = "agent@example.com",
            feedbackText = "",
            metadata = sampleMetadata,
            screenshotName = "shot.png",
        )
        assertEquals("minifile: [v1.0.12] Image attachment", imageOnly.subject)
        assertTrue(imageOnly.body.startsWith("(See attached screenshot: shot.png)"))
    }

    @Test
    fun build_legacyPositionalOverloadStillWorksWithoutTitle() {
        val built = AgentFeedbackBuilder.build(
            "agent@example.com",
            "Legacy feedback line",
            sampleMetadata,
            null,
            null,
        )
        assertEquals("minifile: [v1.0.12] Legacy feedback line", built.subject)
        assertTrue(built.body.startsWith("Legacy feedback line\n\n---\n"))
    }
}
