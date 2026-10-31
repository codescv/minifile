package com.minifile.explorer.core.gdrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import kotlin.concurrent.thread

class GoogleOAuthLoopbackTest {

    @Test
    fun toleratesBrowserPreconnectsFaviconWrongStateValidatesPkceAndNeverEchoesQueryParams() {
        FakeTokenServer().use { tokenServer ->
            val clientId = "loopback-desktop.apps.example"
            val clientSecret = "secret_test_loopback_desktop"
            var faviconResponse = ""
            var wrongStateResponse = ""
            var successResponse = ""
            var capturedAuthUrl = ""

            val loopback = GoogleOAuthLoopback(
                clientId = clientId,
                clientSecret = clientSecret,
                tokenEndpoint = tokenServer.tokenEndpoint,
                openBrowser = { authUrl ->
                    capturedAuthUrl = authUrl
                    thread(name = "fake-browser") {
                        val params = parseUrlQuery(authUrl)
                        val redirectUri = params.getValue("redirect_uri")
                        val state = params.getValue("state")
                        val challenge = params.getValue("code_challenge")
                        val challengeMethod = params.getValue("code_challenge_method")

                        tokenServer.registerAuthCode(
                            code = "code_test_pkce_valid_001",
                            spec = FakeTokenServer.IssuedAuthCode(
                                clientId = clientId,
                                redirectUri = redirectUri,
                                codeChallenge = challenge,
                                codeChallengeMethod = challengeMethod,
                                accessToken = "tok_test_loopback_issued_01",
                                refreshToken = "refresh_test_loopback_issued_01",
                                expiresInSec = 3600L,
                            ),
                        )

                        val redirect = URI(redirectUri)
                        val host = redirect.host
                        val port = redirect.port

                        // 1. Two idle browser preconnect sockets closed without sending HTTP bytes
                        Socket(host, port).close()
                        Socket(host, port).use { Thread.sleep(25L) }

                        // 2. Browser requests /favicon.ico -> 404
                        faviconResponse = sendRawHttpGet(host, port, "/favicon.ico")

                        // 3. Callback with wrong state + XSS sentinel -> 400 and zero echoed query text
                        wrongStateResponse = sendRawHttpGet(
                            host,
                            port,
                            "/oauth2callback?code=code_test_wrong&state=wrong_state_XSS_SENTINEL_1",
                        )

                        // 4. Real OAuth redirect with valid state + extra sentinel query param -> 200
                        successResponse = sendRawHttpGet(
                            host,
                            port,
                            "${redirect.path}?code=code_test_pkce_valid_001&state=${urlEncode(state)}&probe=QUERY_ECHO_SENTINEL_2",
                        )
                    }
                },
            )

            val tokens = loopback.start(timeoutMs = 15_000L)
            assertEquals("tok_test_loopback_issued_01", tokens.accessToken)
            assertEquals("refresh_test_loopback_issued_01", tokens.refreshToken)
            assertEquals(DRIVE_SCOPE, tokens.scope)

            val authParams = parseUrlQuery(capturedAuthUrl)
            assertEquals("S256", authParams["code_challenge_method"])
            assertEquals("offline", authParams["access_type"])
            assertEquals("consent", authParams["prompt"])
            assertEquals(DRIVE_SCOPE, authParams["scope"])

            assertTrue("Expected 404 on /favicon.ico, got: $faviconResponse", faviconResponse.startsWith("HTTP/1.1 404"))
            assertTrue("Expected 400 on wrong state, got: $wrongStateResponse", wrongStateResponse.startsWith("HTTP/1.1 400"))
            assertFalse("Wrong-state body must never echo query string", wrongStateResponse.contains("XSS_SENTINEL_1"))

            assertTrue("Expected 200 on valid callback, got: $successResponse", successResponse.startsWith("HTTP/1.1 200"))
            assertFalse("Success HTML must never echo code", successResponse.contains("code_test_pkce_valid_001"))
            assertFalse("Success HTML must never echo state", successResponse.contains(authParams.getValue("state")))
            assertFalse("Success HTML must never echo arbitrary query params", successResponse.contains("QUERY_ECHO_SENTINEL_2"))
        }
    }

    @Test
    fun accessDeniedCallbackThrowsGDriveAuthException() {
        FakeTokenServer().use { tokenServer ->
            val loopback = GoogleOAuthLoopback(
                clientId = "denied.apps.example",
                tokenEndpoint = tokenServer.tokenEndpoint,
                openBrowser = { authUrl ->
                    thread {
                        val params = parseUrlQuery(authUrl)
                        val redirect = URI(params.getValue("redirect_uri"))
                        val state = params.getValue("state")
                        sendRawHttpGet(
                            redirect.host,
                            redirect.port,
                            "${redirect.path}?error=access_denied&state=${urlEncode(state)}",
                        )
                    }
                },
            )

            try {
                loopback.start(timeoutMs = 10_000L)
                fail("Expected GDriveAuthException on error=access_denied")
            } catch (e: GDriveAuthException) {
                assertTrue(e.needsInteraction)
                assertTrue(e.message!!.contains("denied", ignoreCase = true))
            }
        }
    }

    @Test
    fun startTimesOutWhenBrowserNeverCallsBack() {
        val loopback = GoogleOAuthLoopback(
            clientId = "timeout.apps.example",
            openBrowser = { /* user never completes flow */ },
        )
        try {
            loopback.start(timeoutMs = 120L)
            fail("Expected SocketTimeoutException")
        } catch (_: SocketTimeoutException) {
            // expected
        }
    }

    @Test
    fun cancelFromAnotherThreadUnblocksStartImmediately() {
        lateinit var loopback: GoogleOAuthLoopback
        loopback = GoogleOAuthLoopback(
            clientId = "cancel.apps.example",
            openBrowser = {
                thread {
                    Thread.sleep(60L)
                    loopback.cancel()
                }
            },
        )
        try {
            loopback.start(timeoutMs = 30_000L)
            fail("Expected CancellationIOException")
        } catch (_: CancellationIOException) {
            // expected
        }
    }

    private fun sendRawHttpGet(host: String, port: Int, target: String): String {
        Socket(host, port).use { sock ->
            sock.soTimeout = 5_000
            val req = "GET $target HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
            sock.getOutputStream().flush()
            return sock.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun parseUrlQuery(url: String): Map<String, String> {
        val rawQuery = URI(url).rawQuery ?: return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) null
            else URLDecoder.decode(pair.substring(0, eq), "UTF-8") to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        }.toMap()
    }
}
