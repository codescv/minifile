package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.model.InMemoryKeyValueStore
import com.minifile.explorer.data.DefaultGDriveServersRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GDriveServersRepositoryTest {

    @Test
    fun addUpdateRemovePersistenceAndSystemAccountNeverPersistsTokens() = runBlocking {
        var now = 1_760_000_000_000L
        val store = InMemoryKeyValueStore()
        val repo1 = DefaultGDriveServersRepository(store = store, clock = { now })

        val browserBookmark = repo1.add(
            GDriveServerBookmark(
                id = "gd-1",
                name = "",
                email = "alice@example.com",
                authMode = GDriveAuthMode.BROWSER_PKCE,
                accessToken = "tok_test_initial_access_1",
                refreshToken = "refresh_test_initial_1",
                expiresAtMs = now + 3_600_000L,
                clientId = "desktop.apps.example",
                clientSecret = "secret_test_1",
                initialPath = "/Photos/",
            ),
        )
        assertEquals("alice@example.com", browserBookmark.name)
        assertEquals("/Photos", browserBookmark.initialPath)
        assertEquals(now, browserBookmark.createdAt)

        now += 1_000L
        val sysBookmark = repo1.add(
            GDriveServerBookmark(
                id = "gd-sys",
                name = "Phone Google",
                email = "bob@example.com",
                authMode = GDriveAuthMode.SYSTEM_ACCOUNT,
                accountName = "bob@example.com",
                accessToken = "tok_test_should_not_be_saved",
                refreshToken = "refresh_test_should_not_be_saved",
                expiresAtMs = now + 3_600_000L,
            ),
        )
        assertEquals("", sysBookmark.accessToken)
        assertEquals("", sysBookmark.refreshToken)
        assertEquals(0L, sysBookmark.expiresAtMs)

        // updateTokens on SYSTEM_ACCOUNT is a no-op
        repo1.updateTokens("gd-sys", "tok_test_ignored", "refresh_test_ignored", now + 9_999L)
        assertEquals("", repo1.findById("gd-sys")?.accessToken)

        // Reload in a fresh repository instance from the persisted JSON
        val repo2 = DefaultGDriveServersRepository(store = store, clock = { now })
        assertEquals(2, repo2.servers.value.size)
        val reloaded1 = repo2.findById("gd-1")
        assertNotNull(reloaded1)
        assertEquals("tok_test_initial_access_1", reloaded1!!.accessToken)
        assertEquals("refresh_test_initial_1", reloaded1.refreshToken)
        assertEquals("/Photos", reloaded1.initialPath)

        // Remove
        repo2.remove("gd-1")
        assertNull(repo2.findById("gd-1"))
        assertEquals(1, repo2.servers.value.size)
    }

    @Test
    fun skipsUnknownAuthModeEntriesGracefullyOnLoad() {
        val store = InMemoryKeyValueStore()
        store.putString(
            DefaultGDriveServersRepository.PREF_KEY,
            """
            [
              {"id":"ok-1","name":"Valid","email":"a@b.com","authMode":"PASTED_TOKEN","accessToken":"tok_test_valid_000001"},
              {"id":"future-1","name":"Future","email":"c@d.com","authMode":"QUANTUM_PASSKEY_V9"},
              {"id":"","name":"MissingId","authMode":"PASTED_TOKEN"}
            ]
            """.trimIndent(),
        )

        val repo = DefaultGDriveServersRepository(store = store)
        assertEquals(1, repo.servers.value.size)
        assertEquals("ok-1", repo.servers.value.single().id)
    }

    @Test
    fun concurrentUpdateTokensAndBookmarkUpdatesAreThreadSafe() = runBlocking {
        val store = InMemoryKeyValueStore()
        val repo = DefaultGDriveServersRepository(store = store, clock = { 1_000L })
        repo.add(
            GDriveServerBookmark(
                id = "gd-race",
                name = "Work Drive",
                email = "work@example.com",
                authMode = GDriveAuthMode.BROWSER_PKCE,
                accessToken = "tok_test_seed_0",
                refreshToken = "refresh_test_seed_0",
                expiresAtMs = 2_000L,
                clientId = "client.apps.example",
            ),
        )

        // Hammer updateTokens + add/remove of sibling bookmarks concurrently across Default dispatcher
        val jobs = (1..16).map { i ->
            async(Dispatchers.Default) {
                repo.updateTokens(
                    id = "gd-race",
                    accessToken = "tok_test_refreshed_$i",
                    refreshToken = if (i % 2 == 0) "refresh_test_rot_$i" else null,
                    expiresAtMs = 10_000L + i,
                )
                val sib = repo.add(
                    GDriveServerBookmark(
                        id = "sib-$i",
                        name = "Sibling $i",
                        authMode = GDriveAuthMode.PASTED_TOKEN,
                        accessToken = "tok_test_sib_$i",
                    ),
                )
                if (i % 2 == 1) {
                    repo.remove(sib.id)
                }
            }
        }
        jobs.awaitAll()

        val finalMain = repo.findById("gd-race")
        assertNotNull(finalMain)
        assertEquals("Work Drive", finalMain!!.name)
        // Reload from JSON to ensure serialized store was never corrupted under contention
        val reloaded = DefaultGDriveServersRepository(store = store)
        assertEquals(repo.servers.value.map { it.id }.sorted(), reloaded.servers.value.map { it.id }.sorted())
    }
}
