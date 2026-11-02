package com.minifile.explorer.core.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeedbackEmailTargetResolverTest {

    private val gmailCompose = ResolvedEmailActivity(
        FeedbackEmailTargetResolver.GMAIL_PACKAGE,
        "com.google.android.gm.ComposeActivityGmailExternal",
    )
    private val outlookCompose = ResolvedEmailActivity(
        "com.microsoft.office.outlook",
        "com.microsoft.office.outlook.compose.ComposeLauncherActivity",
    )
    private val qqMailCompose = ResolvedEmailActivity(
        "com.tencent.androidqqmail",
        "com.tencent.qqmail.launcher.third.LaunchComposeMail",
    )
    private val aospResolver = ResolvedEmailActivity("android", "com.android.internal.app.ResolverActivity")
    private val oplusResolver = ResolvedEmailActivity("com.oplus.resolver", "com.oplus.resolver.OplusResolverActivity")
    private val intentResolver = ResolvedEmailActivity(
        "com.android.intentresolver",
        "com.android.intentresolver.ChooserActivity",
    )

    private fun payload() = AgentFeedbackBuilder.build(
        recipientEmail = "agent@example.com",
        feedbackText = "ColorOS 弹出 分享1张图片",
        metadata = FeedbackMetadata(versionName = "1.0.15", versionCode = 10015),
        screenshotName = "screenshot.png",
        title = "发送反馈包含图片时弹出分享页面",
    )

    @Test
    fun `system resolvers including AOSP Oplus ColorOS and intentresolver are filtered`() {
        assertTrue(FeedbackEmailTargetResolver.isSystemResolver(aospResolver))
        assertTrue(FeedbackEmailTargetResolver.isSystemResolver(oplusResolver))
        assertTrue(FeedbackEmailTargetResolver.isSystemResolver(intentResolver))
        assertTrue(
            FeedbackEmailTargetResolver.isSystemResolver(
                ResolvedEmailActivity("com.coloros.resolver", "com.coloros.resolver.ResolverActivity"),
            ),
        )
        assertTrue(
            FeedbackEmailTargetResolver.isSystemResolver(
                ResolvedEmailActivity("com.heytap.share", "com.heytap.share.ChooserActivityProxy"),
            ),
        )
        assertTrue(FeedbackEmailTargetResolver.isSystemResolver(ResolvedEmailActivity("", "x")))
        assertFalse(FeedbackEmailTargetResolver.isSystemResolver(gmailCompose))
        assertFalse(FeedbackEmailTargetResolver.isSystemResolver(outlookCompose))
    }

    @Test
    fun `default mailto package ignores resolver and untrusted OEM packages`() {
        val mailto = listOf(gmailCompose, outlookCompose)
        assertNull(FeedbackEmailTargetResolver.defaultEmailPackage(aospResolver, mailto))
        assertNull(FeedbackEmailTargetResolver.defaultEmailPackage(oplusResolver, mailto))
        assertNull(FeedbackEmailTargetResolver.defaultEmailPackage(null, mailto))
        // OEM system namespace that is not a real mailto: handler → ignored.
        assertNull(
            FeedbackEmailTargetResolver.defaultEmailPackage(
                ResolvedEmailActivity("com.oplus.share", "com.oplus.share.ShareEntry"),
                mailto,
            ),
        )
        // OEM email client that genuinely answers mailto: → trusted.
        val coloros = ResolvedEmailActivity("com.coloros.email", "com.coloros.email.ComposeActivity")
        assertEquals(
            "com.coloros.email",
            FeedbackEmailTargetResolver.defaultEmailPackage(coloros, mailto + coloros),
        )
        assertEquals(
            "com.microsoft.office.outlook",
            FeedbackEmailTargetResolver.defaultEmailPackage(outlookCompose, mailto),
        )
    }

    @Test
    fun `packages ordered default first then Gmail then other mailto handlers deduplicated`() {
        val mailto = listOf(qqMailCompose, aospResolver, outlookCompose, gmailCompose, qqMailCompose)
        assertEquals(
            listOf("com.microsoft.office.outlook", "com.google.android.gm", "com.tencent.androidqqmail"),
            FeedbackEmailTargetResolver.orderEmailPackages(outlookCompose, mailto),
        )
        // Resolver as "default" (multiple apps, none chosen) → Gmail first.
        assertEquals(
            listOf("com.google.android.gm", "com.tencent.androidqqmail", "com.microsoft.office.outlook"),
            FeedbackEmailTargetResolver.orderEmailPackages(oplusResolver, mailto),
        )
        // Gmail default → appears once.
        assertEquals(
            listOf("com.google.android.gm", "com.tencent.androidqqmail", "com.microsoft.office.outlook"),
            FeedbackEmailTargetResolver.orderEmailPackages(gmailCompose, mailto),
        )
        // SENDTO fallback ordering without probing an absent Gmail.
        assertEquals(
            listOf("com.tencent.androidqqmail"),
            FeedbackEmailTargetResolver.orderEmailPackages(null, listOf(qqMailCompose), includeGmailIfInstalled = false),
        )
    }

    @Test
    fun `send mime candidates fall back to image wildcard and rfc822`() {
        assertEquals(
            listOf("image/jpeg", "image/*", "*/*", "message/rfc822"),
            FeedbackEmailTargetResolver.sendMimeCandidates("image/jpeg"),
        )
        assertEquals(
            listOf("image/*", "*/*", "message/rfc822"),
            FeedbackEmailTargetResolver.sendMimeCandidates("  "),
        )
    }

    @Test
    fun `finds ACTION_SEND image activity inside discovered mailto packages in priority order`() {
        val mailto = listOf(outlookCompose, gmailCompose, aospResolver)
        val queried = mutableListOf<Pair<String, String>>()
        val sendIndex = mapOf(
            (FeedbackEmailTargetResolver.GMAIL_PACKAGE to "image/jpeg") to listOf(
                ResolvedEmailActivity(FeedbackEmailTargetResolver.GMAIL_PACKAGE, "com.google.android.gm.ComposeActivityGmail"),
                gmailCompose,
            ),
            // Outlook only declares */* for ACTION_SEND.
            ("com.microsoft.office.outlook" to "*/*") to listOf(outlookCompose),
        )
        val targets = FeedbackEmailTargetResolver.resolveComposeTargets(
            defaultMailto = oplusResolver,
            mailtoActivities = mailto,
            attachmentMime = "image/jpeg",
        ) { pkg, mime ->
            queried += pkg to mime
            sendIndex[pkg to mime].orEmpty()
        }

        assertEquals(
            listOf(
                EmailComposeTarget(FeedbackEmailTargetResolver.GMAIL_PACKAGE, gmailCompose.activityName, "image/jpeg"),
                EmailComposeTarget("com.microsoft.office.outlook", outlookCompose.activityName, "image/jpeg"),
            ),
            targets,
        )
        // Every probe is scoped to an email package; the system resolver package is never probed.
        assertTrue(queried.none { it.first == "android" || it.first.startsWith("com.oplus") })
    }

    @Test
    fun `rfc822 only composer keeps rfc822 type and resolver hits within package are ignored`() {
        val pkg = "com.example.mail"
        val target = FeedbackEmailTargetResolver.findSendActivity(
            packageName = pkg,
            attachmentMime = "image/png",
            mailtoActivities = emptyList(),
        ) { p, mime ->
            when (mime) {
                "image/png" -> listOf(ResolvedEmailActivity(p, "com.example.mail.ResolverActivity"))
                "message/rfc822" -> listOf(
                    ResolvedEmailActivity("other.pkg", "other.Compose"),
                    ResolvedEmailActivity(p, "com.example.mail.Compose"),
                )
                else -> emptyList()
            }
        }
        assertEquals(EmailComposeTarget(pkg, "com.example.mail.Compose", "message/rfc822"), target)
    }

    @Test
    fun `uninstalled Gmail without send activity is dropped`() {
        val targets = FeedbackEmailTargetResolver.resolveComposeTargets(
            defaultMailto = null,
            mailtoActivities = listOf(qqMailCompose),
            attachmentMime = "image/jpeg",
        ) { pkg, _ -> if (pkg == qqMailCompose.packageName) listOf(qqMailCompose) else emptyList() }
        assertEquals(listOf(qqMailCompose.packageName), targets.map { it.packageName })
        assertTrue(
            FeedbackEmailTargetResolver.resolveComposeTargets(null, emptyList(), "image/jpeg") { _, _ -> emptyList() }
                .isEmpty(),
        )
    }

    @Test
    fun `attachment send spec is explicit ACTION_SEND with extras grant flag and no selector`() {
        val p = payload()
        val uri = "content://com.minifile.explorer.fileprovider/feedback_cache/screenshot.jpg"
        val spec = FeedbackEmailTargetResolver.buildAttachmentSendSpec(
            payload = p,
            target = EmailComposeTarget(FeedbackEmailTargetResolver.GMAIL_PACKAGE, gmailCompose.activityName, "image/jpeg"),
            attachmentUri = uri,
        )
        assertEquals("android.intent.action.SEND", spec.action)
        assertEquals("com.google.android.gm", spec.packageName)
        assertEquals("com.google.android.gm.ComposeActivityGmailExternal", spec.className)
        assertEquals("image/jpeg", spec.mimeType)
        assertEquals(listOf("agent@example.com"), spec.recipients)
        assertEquals(p.subject, spec.subject)
        assertTrue(spec.subject.startsWith("minifile: [v1.0.15]"))
        assertEquals(p.body, spec.text)
        assertEquals(uri, spec.streamUri)
        assertEquals("feedback_attachment", spec.clipLabel)
        assertTrue(spec.grantReadUriPermission)
        assertFalse(spec.hasSelector)
    }

    @Test
    fun `blank activity name falls back to package scoped intent and blank mime to image wildcard`() {
        val spec = FeedbackEmailTargetResolver.buildAttachmentSendSpec(
            payload = payload(),
            target = EmailComposeTarget("com.example.mail", "", ""),
            attachmentUri = "content://x/y",
        )
        assertEquals("com.example.mail", spec.packageName)
        assertNull(spec.className)
        assertEquals("image/*", spec.mimeType)
        assertFalse(spec.hasSelector)
    }

    @Test
    fun `manifest queries and ToolsScreen launcher target email compose directly without selector`() {
        var root: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (root != null && !(File(root, "settings.gradle.kts").isFile && File(root, "app").isDirectory)) {
            root = root.parentFile
        }
        val repoRoot = checkNotNull(root) { "Could not locate repository root" }
        val manifest = File(repoRoot, "app/src/main/AndroidManifest.xml").readText()
        val tools = File(repoRoot, "app/src/main/java/com/minifile/explorer/ui/screens/ToolsScreen.kt").readText()

        assertTrue(manifest.contains("<queries>"))
        assertTrue(manifest.contains("<data android:scheme=\"mailto\" />"))
        assertTrue(manifest.contains("<data android:mimeType=\"image/*\" />"))
        assertTrue(manifest.contains("<data android:mimeType=\"*/*\" />"))
        assertTrue(manifest.contains("<data android:mimeType=\"message/rfc822\" />"))

        assertTrue(tools.contains("FeedbackEmailTargetResolver.resolveComposeTargets("))
        assertTrue(tools.contains("context.grantUriPermission(target.packageName, attachmentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)"))
        assertTrue(tools.contains("intent.component = ComponentName(spec.packageName, explicitClass)"))
        // The v1.0.15 ACTION_SEND + mailto: selector combo must be gone.
        assertFalse(tools.contains("selector = Intent(Intent.ACTION_SENDTO"))
        assertFalse(tools.contains("useMailtoSelector"))
    }
}
