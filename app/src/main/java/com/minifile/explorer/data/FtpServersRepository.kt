package com.minifile.explorer.data

import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.FtpServerBookmark
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.KeyValueStore
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.vfs.PathUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

interface FtpServersRepository {
    val servers: StateFlow<List<FtpServerBookmark>>

    suspend fun add(
        name: String,
        host: String,
        port: Int = 21,
        anonymous: Boolean = true,
        username: String = "anonymous",
        password: String = "",
        initialPath: String = "/",
    ): FtpServerBookmark

    suspend fun update(server: FtpServerBookmark)
    suspend fun remove(id: String)
    fun findById(id: String): FtpServerBookmark?
}

class DefaultFtpServersRepository(
    private val store: KeyValueStore,
    private val clock: Clock = Clock.SYSTEM,
) : FtpServersRepository {

    companion object {
        const val PREF_KEY = "ftp_servers"
    }

    private val lock = Any()
    private val _servers = MutableStateFlow(loadInitial())
    override val servers: StateFlow<List<FtpServerBookmark>> = _servers.asStateFlow()

    private fun loadInitial(): List<FtpServerBookmark> {
        val raw = store.getString(PREF_KEY, null) ?: return emptyList()
        val parsed = runCatching { MiniJson.parse(raw) }.getOrNull() as? JsonValue.Arr
            ?: return emptyList()
        return parsed.items.mapNotNull { (it as? JsonValue.Obj)?.let(FtpServerBookmark::fromJson) }
    }

    private fun persistList(list: List<FtpServerBookmark>) {
        val json = MiniJson.stringify(JsonValue.Arr(list.map { it.toJson() }))
        store.putString(PREF_KEY, json)
    }

    override suspend fun add(
        name: String,
        host: String,
        port: Int,
        anonymous: Boolean,
        username: String,
        password: String,
        initialPath: String,
    ): FtpServerBookmark = synchronized(lock) {
        val cleanHost = host.trim()
        require(cleanHost.isNotEmpty()) { "Host cannot be empty" }
        val cleanPort = port.coerceIn(1, 65535)
        val cleanName = name.trim().ifEmpty { cleanHost }
        val cleanPath = PathUtils.normalizeLocalPath(initialPath.trim().ifEmpty { "/" })
        val cleanUser = if (anonymous) {
            username.trim().ifEmpty { "anonymous" }
        } else {
            username.trim().ifEmpty { "anonymous" }
        }
        val created = FtpServerBookmark(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            host = cleanHost,
            port = cleanPort,
            anonymous = anonymous,
            username = cleanUser,
            password = if (anonymous) "" else password,
            initialPath = cleanPath,
            createdAt = clock.nowMs(),
        )
        val next = _servers.value + created
        persistList(next)
        _servers.value = next
        created
    }

    override suspend fun update(server: FtpServerBookmark): Unit = synchronized(lock) {
        val cleanHost = server.host.trim()
        if (cleanHost.isEmpty()) return
        val normalized = server.copy(
            name = server.name.trim().ifEmpty { cleanHost },
            host = cleanHost,
            port = server.port.coerceIn(1, 65535),
            username = server.username.trim().ifEmpty { "anonymous" },
            password = if (server.anonymous) "" else server.password,
            initialPath = PathUtils.normalizeLocalPath(server.initialPath.trim().ifEmpty { "/" }),
        )
        val next = _servers.value.map { if (it.id == normalized.id) normalized else it }
        persistList(next)
        _servers.value = next
    }

    override suspend fun remove(id: String): Unit = synchronized(lock) {
        val next = _servers.value.filterNot { it.id == id }
        persistList(next)
        _servers.value = next
    }

    override fun findById(id: String): FtpServerBookmark? =
        _servers.value.firstOrNull { it.id == id }
}
