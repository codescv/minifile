package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GDriveTokenParserTest {

    private val fixedNowMs = 1_760_000_000_000L
    private val clock = Clock { fixedNowMs }

    @Test
    fun parsesRcloneAuthorizeJsonWithZuluAndOffsetExpiry() {
        val rcloneZulu = """
            {
              "access_token": "tok_test_rclone_access_000001",
              "token_type": "Bearer",
              "refresh_token": "refresh_test_rclone_000001",
              "expiry": "2026-10-31T12:00:00Z"
            }
        """.trimIndent()
        val parsedZ = GDriveTokenParser.parse(rcloneZulu, clock)
        assertNotNull(parsedZ)
        assertEquals("tok_test_rclone_access_000001", parsedZ!!.accessToken)
        assertEquals("refresh_test_rclone_000001", parsedZ.refreshToken)
        assertEquals(Instant.parse("2026-10-31T12:00:00Z").toEpochMilli(), parsedZ.expiresAtMs)

        val rcloneOffset = """
            {"access_token":"tok_test_rclone_access_000002","refresh_token":"refresh_test_rclone_000002","expiry":"2026-10-31T20:00:00.250+08:00"}
        """.trimIndent()
        val parsedOffset = GDriveTokenParser.parse(rcloneOffset, clock)
        assertNotNull(parsedOffset)
        assertEquals(
            Instant.parse("2026-10-31T12:00:00.250Z").toEpochMilli(),
            parsedOffset!!.expiresAtMs,
        )
    }

    @Test
    fun parsesCompanionJsonWithClientIdSecretAndScope() {
        val companionJson = """
            {
              "access_token": "tok_test_companion_access_1234",
              "refresh_token": "refresh_test_companion_5678",
              "token_type": "Bearer",
              "expiry": "2026-11-01T00:00:00Z",
              "client_id": "123456-desktop.apps.example",
              "client_secret": "secret_test_desktop_value",
              "scope": "https://www.googleapis.com/auth/drive"
            }
        """.trimIndent()
        val parsed = GDriveTokenParser.parse(companionJson, clock)
        assertNotNull(parsed)
        assertEquals("tok_test_companion_access_1234", parsed!!.accessToken)
        assertEquals("refresh_test_companion_5678", parsed.refreshToken)
        assertEquals("123456-desktop.apps.example", parsed.clientId)
        assertEquals("secret_test_desktop_value", parsed.clientSecret)
        assertEquals(DRIVE_SCOPE, parsed.scope)
    }

    @Test
    fun parsesRcloneConfSectionAndRcloneAuthorizeBannerOutput() {
        val rcloneConf = """
            [gdrive-work]
            type = drive
            client_id = conf-client.apps.example
            client_secret = secret_test_from_rclone_conf
            scope = drive
            token = {"access_token":"tok_test_from_conf_section_99","token_type":"Bearer","refresh_token":"refresh_test_conf_99","expiry":"2026-12-01T01:02:03Z"}
            team_drive =
        """.trimIndent()
        val parsed = GDriveTokenParser.parse(rcloneConf, clock)
        assertNotNull(parsed)
        assertEquals("tok_test_from_conf_section_99", parsed!!.accessToken)
        assertEquals("refresh_test_conf_99", parsed.refreshToken)
        assertEquals("conf-client.apps.example", parsed.clientId)
        assertEquals("secret_test_from_rclone_conf", parsed.clientSecret)

        val bannerOutput = """
            Paste the following into your remote machine --->
            {"access_token":"tok_test_banner_token_12345","refresh_token":"refresh_test_banner_1","expiry":"2026-12-01T00:00:00Z"}
            <---End paste
        """.trimIndent()
        val fromBanner = GDriveTokenParser.parse(bannerOutput, clock)
        assertNotNull(fromBanner)
        assertEquals("tok_test_banner_token_12345", fromBanner!!.accessToken)
        assertEquals("refresh_test_banner_1", fromBanner.refreshToken)
    }

    @Test
    fun parsesBareTokenWithoutGooglePrefixAndExpiresInOrExpiresAtVariants() {
        // 24-character opaque token with NO vendor prefix
        val bare24 = "tok_test_bare_opaque_123"
        assertEquals(24, bare24.length)
        val parsedBare = GDriveTokenParser.parse("  $bare24\n", clock)
        assertNotNull(parsedBare)
        assertEquals(bare24, parsedBare!!.accessToken)
        assertEquals("", parsedBare.refreshToken)
        assertEquals(0L, parsedBare.expiresAtMs)

        // expires_in (seconds relative to clock)
        val withExpiresIn = GDriveTokenParser.parse(
            """{"access_token":"tok_test_rel_expiry_00001","expires_in":3600}""",
            clock,
        )
        assertEquals(fixedNowMs + 3_600_000L, withExpiresIn?.expiresAtMs)

        // expires_at in epoch seconds
        val withExpiresAtSec = GDriveTokenParser.parse(
            """{"access_token":"tok_test_abs_sec_0000001","expires_at":1770000000}""",
            clock,
        )
        assertEquals(1_770_000_000_000L, withExpiresAtSec?.expiresAtMs)

        // expires_at in epoch milliseconds
        val withExpiresAtMs = GDriveTokenParser.parse(
            """{"access_token":"tok_test_abs_ms_00000001","expires_at":1770000000123}""",
            clock,
        )
        assertEquals(1_770_000_000_123L, withExpiresAtMs?.expiresAtMs)
    }

    @Test
    fun rejectsShortMalformedOrNonTokenStrings() {
        assertNull(GDriveTokenParser.parse("", clock))
        assertNull(GDriveTokenParser.parse("    ", clock))
        // 19 chars (< 20 minimum)
        assertNull(GDriveTokenParser.parse("1234567890123456789", clock))
        // Starts with '{' (malformed JSON)
        assertNull(GDriveTokenParser.parse("{not-valid-json-even-if-long-enough", clock))
        // Starts with '['
        assertNull(GDriveTokenParser.parse("[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]", clock))
        // JSON without access_token
        assertNull(GDriveTokenParser.parse("""{"refresh_token":"refresh_test_only"}""", clock))
        // Contains whitespace inside bare string
        assertNull(GDriveTokenParser.parse("tok_test_has spaces_inside_string", clock))
        // URL pasted by mistake
        assertNull(GDriveTokenParser.parse("https://accounts.google.com/o/oauth2/auth", clock))
    }
}
