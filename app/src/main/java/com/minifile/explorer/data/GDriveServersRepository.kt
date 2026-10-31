package com.minifile.explorer.data

import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.KeyValueStore
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.vfs.PathUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

interface GDriveServersRepository {
    val servers: StateFlow<List<GDriveServerBookmark>>

    suspend fun add(bookmark: GDriveServerBookmark): GDriveServerBookmark
    suspend fun update(bookmark: GDriveServerBookmark)
    fun updateTokens(
        id: String,
        accessToken: String,
        refreshToken: String? = null,
        expiresAtMs: Long,
    )
    suspend fun remove(id: String)
    fun findById(id: String): GDriveServerBookmark?
}

class DefaultGDriveServersRepository(
    private val store: KeyValueStore,
    private val clock: Clock = Clock.SYSTEM,
) : GDriveServersRepository {

    companion object {
        const val PREF_KEY = "gdrive_servers"
    }

    private val lock = Any()
    private val _servers = MutableStateFlow(loadInitial())
    override val servers: StateFlow<List<GDriveServerBookmark>> = _servers.asStateFlow()

    private fun loadInitial(): List<GDriveServerBookmark> {
        val raw = store.getString(PREF_KEY, null) ?: return emptyList()
        val parsed = runCatching { MiniJson.parse(raw) }.getOrNull() as? JsonValue.Arr
            ?: return emptyList()
        return parsed.items.mapNotNull { (it as? JsonValue.Obj)?.let(GDriveServerBookmark::fromJson) }
    }

    private fun persistList(list: List<GDriveServerBookmark>) {
        val json = MiniJson.stringify(JsonValue.Arr(list.map { it.toJson() }))
        store.putString(PREF_KEY, json)
    }

    private fun sanitize(bookmark: GDriveServerBookmark, assignDefaults: Boolean): GDriveServerBookmark {
        val cleanId = bookmark.id.trim().ifEmpty { UUID.randomUUID().toString() }
        val cleanEmail = bookmark.email.trim()
        val cleanAccount = bookmark.accountName.trim()
        val fallbackName = cleanEmail.ifEmpty { cleanAccount.ifEmpty { "Google Drive" } }
        val cleanName = bookmark.name.trim().ifEmpty { fallbackName }
        val cleanPath = PathUtils.normalizeLocalPath(bookmark.initialPath.trim().ifEmpty { "/" })
        val persistTokens = bookmark.authMode != GDriveAuthMode.SYSTEM_ACCOUNT
        val createdAt = if (assignDefaults && bookmark.createdAt <= 0L) clock.nowMs() else bookmark.createdAt
        return bookmark.copy(
            id = cleanId,
            name = cleanName,
            email = cleanEmail,
            accountName = cleanAccount,
            accessToken = if (persistTokens) bookmark.accessToken else "",
            refreshToken = if (persistTokens) bookmark.refreshToken else "",
            expiresAtMs = if (persistTokens) bookmark.expiresAtMs else 0L,
            clientId = bookmark.clientId.trim(),
            clientSecret = bookmark.clientSecret.trim(),
            initialPath = cleanPath,
            createdAt = createdAt,
        )
    }

    override suspend fun add(bookmark: GDriveServerBookmark): GDriveServerBookmark = synchronized(lock) {
        val created = sanitize(bookmark, assignDefaults = true)
        val existingIdx = _servers.value.indexOfFirst { it.id == created.id }
        val next = if (existingIdx >= 0) {
            _servers.value.toMutableList().apply { set(existingIdx, created) }
        } else {
            _servers.value + created
        }
        persistList(next)
        _servers.value = next
        created
    }

    override suspend fun update(bookmark: GDriveServerBookmark): Unit = synchronized(lock) {
        if (bookmark.id.isBlank()) return
        val normalized = sanitize(bookmark, assignDefaults = false)
        val next = _servers.value.map { if (it.id == normalized.id) normalized else it }
        persistList(next)
        _servers.value = next
    }

    override fun updateTokens(
        id: String,
        accessToken: String,
        refreshToken: String?,
        expiresAtMs: Long,
    ): Unit = synchronized(lock) {
        val currentList = _servers.value
        val idx = currentList.indexOfFirst { it.id == id }
        if (idx < 0) return
        val existing = currentList[idx]
        if (existing.authMode == GDriveAuthMode.SYSTEM_ACCOUNT) return
        val updated = existing.copy(
            accessToken = accessToken,
            refreshToken = if (!refreshToken.isNullOrBlank()) refreshToken else existing.refreshToken,
            expiresAtMs = expiresAtMs,
        )
        val next = currentList.toMutableList().apply { set(idx, updated) }
        persistList(next)
        _servers.value = next
    }

    override suspend fun remove(id: String): Unit = synchronized(lock) {
        val next = _servers.value.filterNot { it.id == id }
        persistList(next)
        _servers.value = next
    }

    override fun findById(id: String): GDriveServerBookmark? =
        _servers.value.firstOrNull { it.id == id }
}
