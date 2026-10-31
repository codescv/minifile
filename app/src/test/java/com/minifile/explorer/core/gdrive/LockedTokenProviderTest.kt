package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LockedTokenProviderTest {

    private fun runConcurrentBarrier(parties: Int, action: () -> String): List<String> = runBlocking {
        val ready = CountDownLatch(parties)
        val start = CountDownLatch(1)
        val deferreds = (1..parties).map {
            async(Dispatchers.IO) {
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                action()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        deferreds.awaitAll()
    }

    @Test
    fun concurrent401CoalescesToExactlyOneRefreshAndOnlyInvalidatesStaleTokenOnce() {
        val nowMs = AtomicLong(1_000_000L)
        val clock = Clock { nowMs.get() }
        val refreshCount = AtomicInteger(0)
        val invalidatedTokens = CopyOnWriteArrayList<String>()

        val provider = object : LockedTokenProvider(
            clock = clock,
            initialToken = "tok_test_stale_initial_1",
            initialExpiresAtMs = nowMs.get() + 3_600_000L,
        ) {
            override fun doRefresh(stale: String?): TokenState {
                if (stale != null) {
                    invalidatedTokens.add(stale)
                }
                Thread.sleep(40L)
                val idx = refreshCount.incrementAndGet()
                return TokenState(
                    token = "tok_test_fresh_issued_$idx",
                    expiresAtMs = clock.nowMs() + 3_600_000L,
                )
            }
        }

            val tokens = runConcurrentBarrier(8) {
                provider.accessToken(staleToken = "tok_test_stale_initial_1")
            }

            assertEquals(1, refreshCount.get())
            assertEquals(listOf("tok_test_stale_initial_1"), invalidatedTokens.toList())
            assertTrue(tokens.all { it == "tok_test_fresh_issued_1" })
        }

    @Test
    fun eightConcurrentDriveHttpRequestsGetting401CoalesceIntoOneRefreshAndAllSucceed() = runBlocking {
        FakeDriveServer(initialValidToken = "tok_test_fresh_after_401").use { driveServer ->
            val refreshCount = AtomicInteger(0)
            val provider = object : LockedTokenProvider(
                initialToken = "tok_test_expired_before_401",
                initialExpiresAtMs = System.currentTimeMillis() + 3_600_000L,
            ) {
                override fun doRefresh(stale: String?): TokenState {
                    Thread.sleep(30L)
                    refreshCount.incrementAndGet()
                    return TokenState(
                        token = "tok_test_fresh_after_401",
                        expiresAtMs = System.currentTimeMillis() + 3_600_000L,
                    )
                }
            }
            val http = DriveHttp(
                apiBase = driveServer.apiBase,
                uploadBase = driveServer.uploadBase,
                baseBackoffMs = 5L,
                maxJitterMs = 2L,
            )

            val ready = CountDownLatch(8)
            val start = CountDownLatch(1)
            val emails = (1..8).map {
                async(Dispatchers.IO) {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    val json = http.getJson(
                        tokenProvider = provider,
                        pathSegments = listOf("about"),
                        queryParams = listOf("fields" to "user(emailAddress)"),
                    )
                    json.obj("user")?.str("emailAddress").orEmpty()
                }
            }.also {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
            }.awaitAll()

            assertEquals(1, refreshCount.get())
            assertTrue(emails.all { it == driveServer.userEmail })
        }
    }

    @Test
    fun refreshFailureIsMemoisedForFiveSecondsThenExpires() = runBlocking {
        val nowMs = AtomicLong(2_000_000L)
        val clock = Clock { nowMs.get() }
        val refreshAttempts = AtomicInteger(0)
        var shouldFail = true

        val provider = object : LockedTokenProvider(clock = clock) {
            override fun doRefresh(stale: String?): TokenState {
                val attempt = refreshAttempts.incrementAndGet()
                Thread.sleep(25L)
                if (shouldFail) {
                    throw IOException("Network down #$attempt")
                }
                return TokenState("tok_test_recovered_$attempt", clock.nowMs() + 3_600_000L)
            }
        }

        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val deferredErrors = (1..8).map {
            async(Dispatchers.IO) {
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                try {
                    provider.accessToken()
                    null
                } catch (e: IOException) {
                    e
                }
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        val caught = deferredErrors.awaitAll()

        assertEquals(1, refreshAttempts.get())
        val firstErr = caught.first()!!
        for (err in caught) {
            assertSame("All 8 callers within 5s memo window must receive the memoised exception", firstErr, err)
        }

        // Advance 4 seconds (< 5s window): still memoised, no new refresh call
        nowMs.addAndGet(4_000L)
        shouldFail = false
        try {
            provider.accessToken()
            fail("Expected memoised failure at +4s")
        } catch (e: IOException) {
            assertSame(firstErr, e)
        }
        assertEquals(1, refreshAttempts.get())

        // Advance past 5s window: retries and succeeds
        nowMs.addAndGet(1_500L)
        val recovered = provider.accessToken()
        assertEquals("tok_test_recovered_2", recovered)
        assertEquals(2, refreshAttempts.get())
    }

    @Test
    fun oauthTokenProviderSeedsCachedTokenRefreshesOnExpiryAndThrowsNeedsInteractionOnInvalidGrant() {
        FakeTokenServer().use { tokenServer ->
            val nowMs = AtomicLong(5_000_000L)
            val clock = Clock { nowMs.get() }
            var persistedAccess = ""
            var persistedRefresh: String? = null

            tokenServer.registerRefreshToken(
                "refresh_test_valid_grant_1",
                FakeTokenServer.RefreshGrant(
                    clientId = "desktop.apps.example",
                    clientSecret = "secret_test_desktop_1",
                    nextAccessToken = "tok_test_refreshed_from_server_1",
                    rotatedRefreshToken = "refresh_test_rotated_2",
                    expiresInSec = 3600L,
                ),
            )

            val oauth = OAuthTokenProvider(
                clientId = "desktop.apps.example",
                clientSecret = "secret_test_desktop_1",
                initialAccessToken = "tok_test_seeded_initial_1",
                initialRefreshToken = "refresh_test_valid_grant_1",
                initialExpiresAtMs = nowMs.get() + 600_000L,
                tokenEndpoint = tokenServer.tokenEndpoint,
                clock = clock,
                onTokensRefreshed = { acc, ref, _ ->
                    persistedAccess = acc
                    persistedRefresh = ref
                },
            )

            // Seeded token is fresh -> 0 network requests
            assertEquals("tok_test_seeded_initial_1", oauth.accessToken())
            assertEquals(0, tokenServer.requestCount.get())

            // Advance past expiry -> refreshes once via FakeTokenServer
            nowMs.addAndGet(600_000L)
            assertEquals("tok_test_refreshed_from_server_1", oauth.accessToken())
            assertEquals(1, tokenServer.requestCount.get())
            assertEquals("tok_test_refreshed_from_server_1", persistedAccess)
            assertEquals("refresh_test_rotated_2", persistedRefresh)

            // Simulate revoked refresh token (invalid_grant) -> GDriveAuthException(needsInteraction = true)
            tokenServer.failInvalidGrant.set(true)
            nowMs.addAndGet(3_700_000L)
            try {
                oauth.accessToken()
                fail("Expected GDriveAuthException on invalid_grant")
            } catch (e: GDriveAuthException) {
                assertTrue(e.needsInteraction)
            }
        }
    }

    @Test
    fun second401AfterTokenRefreshThrowsGDriveAuthExceptionNeedsInteraction() = runBlocking {
        FakeDriveServer(initialValidToken = "tok_test_only_server_accepts").use { driveServer ->
            val refreshCalls = AtomicInteger(0)
            // Provider returns tokens that the server does NOT accept -> two consecutive 401s
            val badProvider = object : LockedTokenProvider(
                initialToken = "tok_test_bad_initial_token",
                initialExpiresAtMs = System.currentTimeMillis() + 3_600_000L,
            ) {
                override fun doRefresh(stale: String?): TokenState {
                    refreshCalls.incrementAndGet()
                    return TokenState(
                        token = "tok_test_still_rejected_token",
                        expiresAtMs = System.currentTimeMillis() + 3_600_000L,
                    )
                }
            }

            val http = DriveHttp(
                apiBase = driveServer.apiBase,
                uploadBase = driveServer.uploadBase,
                baseBackoffMs = 5L,
                maxJitterMs = 2L,
            )

            try {
                http.getJson(
                    tokenProvider = badProvider,
                    pathSegments = listOf("about"),
                    queryParams = listOf("fields" to "user(emailAddress)"),
                )
                fail("Expected GDriveAuthException after second 401")
            } catch (e: GDriveAuthException) {
                assertTrue(e.needsInteraction)
                assertEquals(1, refreshCalls.get())
            }
        }
    }
}
