package com.minifile.explorer.data.shizuku

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import moe.shizuku.api.BinderContainer

/**
 * Lightweight zero-dependency ContentProvider compatible with Shizuku Manager (`moe.shizuku.privileged.api`).
 *
 * When Shizuku's server daemon (`shell` UID 2000 or `root` UID 0) detects our process starting,
 * it invokes `call("sendBinder", null, extras)` with `moe.shizuku.privileged.api.intent.extra.BINDER`.
 */
class MiniFileShizukuProvider : ContentProvider() {

    companion object {
        const val METHOD_SEND_BINDER = "sendBinder"
        const val METHOD_GET_BINDER = "getBinder"
        const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
        const val ACTION_BINDER_RECEIVED = "moe.shizuku.api.action.BINDER_RECEIVED"
    }

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        check(!info.multiprocess) { "android:multiprocess must be false for MiniFileShizukuProvider" }
        check(info.exported) { "android:exported must be true for MiniFileShizukuProvider" }
        PrivilegedShellBridge.init(context)
    }

    override fun onCreate(): Boolean {
        context?.let { PrivilegedShellBridge.init(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        extras?.classLoader = BinderContainer::class.java.classLoader
        val reply = Bundle()
        when (method) {
            METHOD_SEND_BINDER -> {
                if (extras == null) return null
                val binder = extractBinder(extras)
                if (binder != null) {
                    PrivilegedShellBridge.onBinderReceived(
                        newBinder = binder,
                        packageName = context?.packageName.orEmpty(),
                    )
                }
            }

            METHOD_GET_BINDER -> {
                val existing = PrivilegedShellBridge.currentBinder()
                if (existing == null || !existing.pingBinder()) {
                    return null
                }
                reply.putParcelable(EXTRA_BINDER, BinderContainer(existing))
            }
        }
        return reply
    }

    private fun extractBinder(extras: Bundle): IBinder? {
        val fromContainer: BinderContainer? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(EXTRA_BINDER, BinderContainer::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(EXTRA_BINDER)
            }
        }.getOrNull()
        if (fromContainer?.binder != null) {
            return fromContainer.binder
        }
        return runCatching { extras.getBinder(EXTRA_BINDER) }.getOrNull()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
