package com.minifile.explorer.data

import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.KeyValueStore
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

interface FavoritesRepository {
    val favorites: StateFlow<List<Favorite>>
    suspend fun add(name: String, path: VfsPath, color: Int? = null): Favorite
    suspend fun remove(id: String)
    suspend fun rename(id: String, name: String)
    suspend fun reorder(ids: List<String>)
    suspend fun touch(id: String)
    fun isFavorite(path: VfsPath): Boolean
    fun findByPath(path: VfsPath): Favorite?
    suspend fun exportJson(): String
    suspend fun importJson(json: String, merge: Boolean)
}

class DefaultFavoritesRepository(
    private val store: KeyValueStore,
    private val clock: Clock = Clock.SYSTEM,
    private val defaultPrimaryRoot: String = "/storage/emulated/0",
) : FavoritesRepository {

    companion object {
        const val PREF_KEY = "favorites"
    }

    private val lock = Any()
    private val _favorites = MutableStateFlow(loadInitial())
    override val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private fun loadInitial(): List<Favorite> {
        val raw = store.getString(PREF_KEY, null)
        if (raw == null) {
            val now = clock.nowMs()
            val seeded = listOf(
                Favorite(
                    id = "seed-download",
                    name = "Download",
                    path = VfsPath.Local("$defaultPrimaryRoot/Download"),
                    createdAt = now,
                    lastUsedAt = now,
                    order = 0,
                ),
                Favorite(
                    id = "seed-camera",
                    name = "Camera",
                    path = VfsPath.Local("$defaultPrimaryRoot/DCIM/Camera"),
                    createdAt = now,
                    lastUsedAt = now - 1,
                    order = 1,
                ),
                Favorite(
                    id = "seed-documents",
                    name = "Documents",
                    path = VfsPath.Local("$defaultPrimaryRoot/Documents"),
                    createdAt = now,
                    lastUsedAt = now - 2,
                    order = 2,
                ),
            )
            persistList(seeded)
            return seeded
        }
        return parseFavoritesJson(raw)
    }

    private fun parseFavoritesJson(raw: String): List<Favorite> {
        val parsed = runCatching { MiniJson.parse(raw) }.getOrNull() as? JsonValue.Arr
            ?: return emptyList()
        return parsed.items
            .mapNotNull { (it as? JsonValue.Obj)?.let(Favorite::fromJson) }
            .sortedBy { it.order }
    }

    private fun persistList(list: List<Favorite>) {
        val normalized = list.mapIndexed { idx, fav -> fav.copy(order = idx) }
        val json = MiniJson.stringify(JsonValue.Arr(normalized.map { it.toJson() }))
        store.putString(PREF_KEY, json)
    }

    override suspend fun add(name: String, path: VfsPath, color: Int?): Favorite = synchronized(lock) {
        val current = _favorites.value
        val existing = current.firstOrNull { it.path == path }
        if (existing != null) {
            val updated = existing.copy(
                name = name.trim().ifEmpty { path.displayName },
                lastUsedAt = clock.nowMs(),
                color = color ?: existing.color,
            )
            val next = current.map { if (it.id == existing.id) updated else it }
            persistList(next)
            _favorites.value = next
            return updated
        }
        val now = clock.nowMs()
        val created = Favorite(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { path.displayName },
            path = path,
            createdAt = now,
            lastUsedAt = now,
            order = current.size,
            color = color,
        )
        val next = current + created
        persistList(next)
        _favorites.value = next
        created
    }

    override suspend fun remove(id: String): Unit = synchronized(lock) {
        val next = _favorites.value
            .filterNot { it.id == id }
            .mapIndexed { idx, fav -> fav.copy(order = idx) }
        persistList(next)
        _favorites.value = next
    }

    override suspend fun rename(id: String, name: String): Unit = synchronized(lock) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val next = _favorites.value.map { fav ->
            if (fav.id == id) fav.copy(name = clean) else fav
        }
        persistList(next)
        _favorites.value = next
    }

    override suspend fun reorder(ids: List<String>): Unit = synchronized(lock) {
        val byId = _favorites.value.associateBy { it.id }
        val ordered = mutableListOf<Favorite>()
        for (id in ids) {
            byId[id]?.let(ordered::add)
        }
        for (fav in _favorites.value) {
            if (fav.id !in ids) ordered.add(fav)
        }
        val indexed = ordered.mapIndexed { idx, fav -> fav.copy(order = idx) }
        persistList(indexed)
        _favorites.value = indexed
    }

    override suspend fun touch(id: String): Unit = synchronized(lock) {
        val now = clock.nowMs()
        val next = _favorites.value.map { fav ->
            if (fav.id == id) fav.copy(lastUsedAt = now) else fav
        }
        persistList(next)
        _favorites.value = next
    }

    override fun isFavorite(path: VfsPath): Boolean =
        _favorites.value.any { it.path == path }

    override fun findByPath(path: VfsPath): Favorite? =
        _favorites.value.firstOrNull { it.path == path }

    override suspend fun exportJson(): String = synchronized(lock) {
        MiniJson.stringify(JsonValue.Arr(_favorites.value.map { it.toJson() }), indent = 2)
    }

    override suspend fun importJson(json: String, merge: Boolean): Unit = synchronized(lock) {
        val parsed = MiniJson.parse(json) as? JsonValue.Arr
            ?: throw IllegalArgumentException("Favorites JSON must be an array")
        val imported = parsed.items.mapNotNull { (it as? JsonValue.Obj)?.let(Favorite::fromJson) }
        val combined = if (merge) {
            val existingByPath = _favorites.value.associateBy { it.path }.toMutableMap()
            for (fav in imported) {
                existingByPath[fav.path] = fav
            }
            existingByPath.values.toList()
        } else {
            imported
        }
        val indexed = combined.mapIndexed { idx, fav -> fav.copy(order = idx) }
        persistList(indexed)
        _favorites.value = indexed
    }
}
