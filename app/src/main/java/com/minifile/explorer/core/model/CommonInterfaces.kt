package com.minifile.explorer.core.model

import com.minifile.explorer.core.vfs.VfsPath
import java.util.concurrent.ConcurrentHashMap

fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

fun interface MediaScanNotifier {
    fun rescan(path: VfsPath)

    companion object {
        val NOOP: MediaScanNotifier = MediaScanNotifier { }
    }
}

fun interface Logger {
    fun log(tag: String, message: String, error: Throwable?)

    companion object {
        val NOOP: Logger = Logger { _, _, _ -> }
    }
}

interface KeyValueStore {
    fun getString(key: String, default: String? = null): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int = 0): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long = 0L): Long
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = ConcurrentHashMap<String, Any>()

    override fun getString(key: String, default: String?): String? =
        (map[key] as? String) ?: default

    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        (map[key] as? Boolean) ?: default

    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    override fun getInt(key: String, default: Int): Int =
        (map[key] as? Number)?.toInt() ?: default

    override fun putInt(key: String, value: Int) {
        map[key] = value
    }

    override fun getLong(key: String, default: Long): Long =
        (map[key] as? Number)?.toLong() ?: default

    override fun putLong(key: String, value: Long) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}
