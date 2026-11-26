package com.minifile.explorer.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogRedactorTest {

    private fun r(s: String) = DiagnosticLogRedactor.redact(s)

    @Test
    fun wifiQrPasswordIsRedactedAndOtherFieldsKept() {
        assertEquals("WIFI:T:WPA;S:AndroidShare_1234;P:<redacted>;;", r("WIFI:T:WPA;S:AndroidShare_1234;P:hunter2pass;;"))
        // P first, escaped `;` inside the passphrase, lowercase prefix.
        assertEquals("WIFI:P:<redacted>;T:SAE;S:x;;", r("WIFI:P:se\\;cr\\:et;T:SAE;S:x;;"))
        assertEquals("qr=wifi:S:My\\;Net;T:WPA;P:<redacted>;H:false;;", r("qr=wifi:S:My\\;Net;T:WPA;P:abc;H:false;;"))
        // SSID that merely contains "P:" is not treated as the password field.
        assertEquals("WIFI:S:AP:1;P:<redacted>;;", r("WIFI:S:AP:1;P:zzz;;"))
    }

    @Test
    fun jsonAndKeyValuePasswordsAreRedacted() {
        assertEquals("""{"t":"hotspot","ssid":"S","pass":"<redacted>","sec":"wpa2"}""",
            r("""{"t":"hotspot","ssid":"S","pass":"p@ss \"w0rd\"","sec":"wpa2"}"""))
        assertEquals("""{"password" : "<redacted>"}""", r("""{"password" : "abc"}"""))
        assertEquals("ssid=X pass=<redacted> sec=wpa2", r("ssid=X pass=Secret123 sec=wpa2"))
        assertEquals("Hotspot(ssid=X, pass=<redacted>, sec=wpa2)", r("Hotspot(ssid=X, pass=Secret123, sec=wpa2)"))
        assertEquals("ftp_password=<redacted>&user=a", r("ftp_password=xyz&user=a"))
        assertEquals("password: <redacted>", r("password: hunter2"))
        assertEquals("pin=<redacted> pinned=true", r("pin=4321 pinned=true"))
        // Not a secret key: `bypass=` / `passive=` must stay untouched.
        assertEquals("bypass=1 passive=true sec=wpa3", r("bypass=1 passive=true sec=wpa3"))
    }

    @Test
    fun oauthMaterialAndBearerAreRedacted() {
        assertEquals("Authorization: Bearer <redacted>", r("Authorization: Bearer abcdefgh12345.xyz-_"))
        assertEquals("""{"access_token":"<redacted>","refresh_token":"<redacted>","expires_in":3599}""",
            r("""{"access_token":"tok_aaaaaaaa","refresh_token":"rt_bbbbbbbb","expires_in":3599}"""))
        assertEquals("access_token=<redacted>&refresh_token=<redacted>&client_secret=<redacted>",
            r("access_token=AAAA1111&refresh_token=BBBB2222&client_secret=CCCC3333"))
        // Prose "Basic auth" is not a credential.
        assertEquals("Basic auth disabled", r("Basic auth disabled"))
    }

    @Test
    fun sessionTokensAreTruncatedToFourCharPrefix() {
        val tok = "0123456789abcdef0123456789abcdef"
        assertEquals("""{"t":"hello","tok":"0123…","lan":[]}""", r("""{"t":"hello","tok":"$tok","lan":[]}"""))
        assertEquals("POST /api/peer/announce?tok=0123…&port=8080&id=x", r("POST /api/peer/announce?tok=$tok&port=8080&id=x"))
        assertEquals("gate arm session tok=0123… ttlMs=600000", r("gate arm session tok=$tok ttlMs=600000"))
        assertEquals("token=abcd…", r("token=abcdefgh"))
        assertEquals("tok=…", r("tok=abc"))
        assertEquals("tok=", r("tok="))
        // `access_token` is fully redacted, not prefix-truncated.
        assertEquals("access_token=<redacted>", r("access_token=abcdefgh"))
        assertFalse(r("tok=$tok").contains(tok))
        // An escaped newline (`\\n` in a single-line entry) or `)` ends the token value.
        assertEquals("tok=0123…\\nnext line", r("tok=$tok\\nnext line"))
        assertEquals("Hello(id=x, tok=0123…)", r("Hello(id=x, tok=$tok)"))
    }

    @Test
    fun redactionIsIdempotent() {
        val samples = listOf(
            "WIFI:T:WPA;S:s;P:secret;;",
            """{"pass":"x","tok":"0123456789"}""",
            "tok=0123456789 pass=abc Authorization: Bearer abcdefghijkl",
            "access_token=xxxxxxxxxx token=yyyyyyyyyy",
        )
        for (s in samples) {
            val once = r(s)
            assertEquals(once, r(once))
        }
    }

    @Test
    fun tokenPrefixEdgeCases() {
        assertEquals("", DiagnosticLogRedactor.tokenPrefix(null))
        assertEquals("", DiagnosticLogRedactor.tokenPrefix(""))
        assertEquals("…", DiagnosticLogRedactor.tokenPrefix("abcd"))
        assertEquals("abcd…", DiagnosticLogRedactor.tokenPrefix("abcde"))
        assertEquals("abcd…", DiagnosticLogRedactor.tokenPrefix("abcd…"))
    }

    @Test
    fun plainDiagnosticTextIsUntouched() {
        val s = "send begin -> 192.168.49.1:8080 files=2 bytes=1048576 os=ios timeoutMs=20000"
        assertEquals(s, r(s))
        assertTrue(r("").isEmpty())
    }
}
