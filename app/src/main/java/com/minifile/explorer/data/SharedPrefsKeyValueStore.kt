package com.minifile.explorer.data

import android.content.Context
import android.content.SharedPreferences
import com.minifile.explorer.core.model.KeyValueStore

class SharedPrefsKeyValueStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : KeyValueStore {

    companion object {
        const val PREFS_NAME = "minifile"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun getString(key: String, default: String?): String? =
        prefs.getString(key, default)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int =
        prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long =
        prefs.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
