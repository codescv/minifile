package com.minifile.explorer.data.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.PrivilegedLocalFallback
import com.minifile.explorer.core.vfs.PrivilegedShellCodec
import com.minifile.explorer.core.vfs.VfsNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.shizuku.api.BinderContainer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class PrivilegedAccessState(
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val shizukuUid: Int = -1,
    val rootAvailable: Boolean = false,
) {
    val isActive: Boolean
        get() = (shizukuRunning && shizukuGranted) || rootAvailable
}

/**
 * Zero-external-dependency privileged shell VFS bridge supporting:
 * 1. **Shizuku** (`moe.shizuku.server.IShizukuService` Binder IPC via `MiniFileShizukuProvider`)
 * 2. **Root** (`su -c` shell fallback when `su` binary is available and authorized)
 */
object PrivilegedShellBridge {

    const val SHIZUKU_MANAGER_PKG = "moe.shizuku.privileged.api"
    const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    const val SHIZUKU_WEBSITE_URL = "https://shizuku.rikka.app/"

    private const val DESCRIPTOR_SERVICE = "moe.shizuku.server.IShizukuService"
    private const val DESCRIPTOR_APPLICATION = "moe.shizuku.server.IShizukuApplication"
    private const val DESCRIPTOR_REMOTE_PROCESS = "moe.shizuku.server.IRemoteProcess"

    private const val SERVER_API_VERSION = 13

    // IShizukuService transaction codes (FIRST_CALL_TRANSACTION + aidl_id)
    private const val TX_GET_UID = IBinder.FIRST_CALL_TRANSACTION + 3 // = 4
    private const val TX_CHECK_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 4 // = 5
    private const val TX_NEW_PROCESS = IBinder.FIRST_CALL_TRANSACTION + 7 // = 8
    private const val TX_ATTACH_APPLICATION_V11 = IBinder.FIRST_CALL_TRANSACTION + 13 // = 14
    private const val TX_REQUEST_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 14 // = 15
    private const val TX_CHECK_SELF_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 15 // = 16
    private const val TX_ATTACH_APPLICATION_V13 = IBinder.FIRST_CALL_TRANSACTION + 17 // = 18

    // IShizukuApplication callback transaction codes
    private const val TX_APP_BIND_APPLICATION = IBinder.FIRST_CALL_TRANSACTION + 1 // = 2
    private const val TX_APP_DISPATCH_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 2 // = 3
    private const val TX_APP_SHOW_CONFIRMATION = IBinder.FIRST_CALL_TRANSACTION + 10000

    // IRemoteProcess transaction codes (sequential starting at FIRST_CALL_TRANSACTION)
    private const val TX_PROC_GET_OUTPUT_STREAM = IBinder.FIRST_CALL_TRANSACTION + 0 // = 1
    private const val TX_PROC_GET_INPUT_STREAM = IBinder.FIRST_CALL_TRANSACTION + 1 // = 2
    private const val TX_PROC_GET_ERROR_STREAM = IBinder.FIRST_CALL_TRANSACTION + 2 // = 3
    private const val TX_PROC_WAIT_FOR = IBinder.FIRST_CALL_TRANSACTION + 3 // = 4
    private const val TX_PROC_DESTROY = IBinder.FIRST_CALL_TRANSACTION + 5 // = 6

    private const val KEY_ATTACH_PACKAGE_NAME = "shizuku:attach-package-name"
    private const val KEY_ATTACH_API_VERSION = "shizuku:attach-api-version"
    private const val KEY_BIND_SERVER_UID = "shizuku:attach-reply-uid"
    private const val KEY_BIND_PERMISSION_GRANTED = "shizuku:attach-reply-permission-granted"
    private const val KEY_REQUEST_PERM_ALLOWED = "shizuku:request-permission-reply-allowed"

    private val SU_CANDIDATE_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/su/bin/su",
    )

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var shizukuBinder: IBinder? = null

    @Volatile
    private var shizukuGrantedCached: Boolean = false

    @Volatile
    private var shizukuUidCached: Int = -1

    @Volatile
    private var rootProbeCache: Boolean? = null

    private val _state = MutableStateFlow(PrivilegedAccessState())
    val state: StateFlow<PrivilegedAccessState> = _state.asStateFlow()

    private val deathRecipient = IBinder.DeathRecipient {
        synchronized(lock) {
            shizukuBinder = null
            shizukuGrantedCached = false
            shizukuUidCached = -1
            publishStateLocked()
        }
    }

    private val applicationCallbackBinder: Binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                TX_APP_BIND_APPLICATION -> {
                    runCatching {
                        data.enforceInterface(DESCRIPTOR_APPLICATION)
                        val bundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                        if (bundle != null) {
                            synchronized(lock) {
                                shizukuUidCached = bundle.getInt(KEY_BIND_SERVER_UID, shizukuUidCached)
                                shizukuGrantedCached = bundle.getBoolean(KEY_BIND_PERMISSION_GRANTED, shizukuGrantedCached)
                                publishStateLocked()
                            }
                        }
                    }
                    true
                }

                TX_APP_DISPATCH_PERMISSION -> {
                    runCatching {
                        data.enforceInterface(DESCRIPTOR_APPLICATION)
                        data.readInt() // requestCode
                        val bundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                        val allowed = bundle?.getBoolean(KEY_REQUEST_PERM_ALLOWED, false) ?: false
                        synchronized(lock) {
                            shizukuGrantedCached = allowed
                            publishStateLocked()
                        }
                    }
                    true
                }

                TX_APP_SHOW_CONFIRMATION -> {
                    reply?.writeNoException()
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun createLocalFallback(context: Context): PrivilegedLocalFallback {
        init(context)
        val pkg = context.applicationContext.packageName
        return object : PrivilegedLocalFallback {
            override val ownPackageName: String = pkg
            override fun isAvailable(): Boolean = hasPrivilegedAccess(context)
            override fun stat(path: String): VfsNode? = this@PrivilegedShellBridge.stat(path)
            override fun exists(path: String): Boolean = this@PrivilegedShellBridge.exists(path)
            override fun listDirectory(path: String, includeHidden: Boolean): List<VfsNode>? =
                this@PrivilegedShellBridge.listDirectory(path, includeHidden)
            override fun mkdir(path: String): Boolean = this@PrivilegedShellBridge.mkdir(path)
            override fun createFile(path: String): Boolean = this@PrivilegedShellBridge.createFile(path)
            override fun delete(path: String): Boolean = this@PrivilegedShellBridge.delete(path, recursive = false)
            override fun rename(srcPath: String, dstPath: String): Boolean =
                this@PrivilegedShellBridge.rename(srcPath, dstPath)
            override fun setLastModified(path: String, epochMs: Long): Boolean =
                this@PrivilegedShellBridge.setLastModified(path, epochMs)
            override fun openInput(path: String, offset: Long): InputStream =
                this@PrivilegedShellBridge.openInput(path, offset)
            override fun openOutput(path: String, append: Boolean): OutputStream =
                this@PrivilegedShellBridge.openOutput(path, append)
        }
    }

    fun currentBinder(): IBinder? = shizukuBinder?.takeIf { it.pingBinder() }

    fun onBinderReceived(newBinder: IBinder?, packageName: String) {
        synchronized(lock) {
            if (shizukuBinder === newBinder) return
            shizukuBinder?.let { old -> runCatching { old.unlinkToDeath(deathRecipient, 0) } }
            shizukuBinder = newBinder
            shizukuGrantedCached = false
            shizukuUidCached = -1
            if (newBinder != null && newBinder.pingBinder()) {
                runCatching { newBinder.linkToDeath(deathRecipient, 0) }
                val pkg = packageName.ifEmpty { appContext?.packageName.orEmpty() }
                runCatching {
                    if (!attachApplicationV13(newBinder, pkg)) {
                        attachApplicationV11(newBinder, pkg)
                    }
                }
                // Query UID & permission status eagerly if server already answered or uses pre-v13 sync checks
                runCatching {
                    if (shizukuUidCached < 0) {
                        shizukuUidCached = queryRemoteUid(newBinder)
                    }
                    if (!shizukuGrantedCached) {
                        shizukuGrantedCached = queryRemoteSelfPermission(newBinder)
                    }
                }
            }
            publishStateLocked()
        }
    }

    fun tryRequestBinder(context: Context): Boolean {
        init(context)
        if (isShizukuRunning()) return true
        val cr = context.contentResolver
        val ownAuthority = "content://${context.packageName}.shizuku"
        val candidateUris = listOf(
            ownAuthority,
            "content://moe.shizuku.privileged.api.shizuku",
            "content://moe.shizuku.privileged.api.provider",
        )
        for (uriStr in candidateUris) {
            val reply = runCatching {
                cr.call(Uri.parse(uriStr), MiniFileShizukuProvider.METHOD_GET_BINDER, null, Bundle())
            }.getOrNull() ?: continue
            reply.classLoader = BinderContainer::class.java.classLoader
            val binder = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    reply.getParcelable(MiniFileShizukuProvider.EXTRA_BINDER, BinderContainer::class.java)?.binder
                } else {
                    @Suppress("DEPRECATION")
                    reply.getParcelable<BinderContainer>(MiniFileShizukuProvider.EXTRA_BINDER)?.binder
                }
            }.getOrNull() ?: runCatching { reply.getBinder(MiniFileShizukuProvider.EXTRA_BINDER) }.getOrNull()
            if (binder != null && binder.pingBinder()) {
                onBinderReceived(binder, context.packageName)
                return true
            }
        }
        return isShizukuRunning()
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PKG, 0)
            true
        }.getOrDefault(false)
    }

    fun isShizukuRunning(): Boolean = shizukuBinder?.pingBinder() == true

    fun isShizukuGranted(context: Context? = appContext): Boolean {
        val binder = shizukuBinder?.takeIf { it.pingBinder() } ?: return false
        if (shizukuGrantedCached) return true
        val remoteGranted = runCatching { queryRemoteSelfPermission(binder) }.getOrDefault(false)
        if (remoteGranted) {
            synchronized(lock) {
                shizukuGrantedCached = true
                publishStateLocked()
            }
            return true
        }
        val ctx = context ?: appContext
        if (ctx != null && ctx.checkSelfPermission(SHIZUKU_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            // Standard runtime check fallback for older Shizuku versions
            val permCheck = runCatching { queryRemotePermission(binder, "android.permission.GRANT_RUNTIME_PERMISSIONS") }
                .getOrDefault(false)
            if (permCheck) {
                synchronized(lock) {
                    shizukuGrantedCached = true
                    publishStateLocked()
                }
                return true
            }
        }
        return false
    }

    fun requestShizukuPermission(requestCode: Int = 1001): Boolean {
        val binder = shizukuBinder?.takeIf { it.pingBinder() } ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE)
            data.writeInt(requestCode)
            val ok = binder.transact(TX_REQUEST_PERMISSION, data, reply, 0)
            reply.readException()
            ok
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun isSuBinaryPresent(): Boolean {
        if (SU_CANDIDATE_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }) {
            return true
        }
        val pathEnv = System.getenv("PATH").orEmpty()
        for (dir in pathEnv.split(':')) {
            if (dir.isNotBlank() && runCatching { File(dir, "su").exists() }.getOrDefault(false)) {
                return true
            }
        }
        return false
    }

    fun probeRootAccess(forceReprobe: Boolean = false): Boolean {
        if (!forceReprobe) {
            rootProbeCache?.let { return it }
        }
        if (!isSuBinaryPresent()) {
            rootProbeCache = false
            return false
        }
        val ok = runCatching {
            val proc = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0 && out.contains("uid=0")
            }
        }.getOrDefault(false)
        synchronized(lock) {
            rootProbeCache = ok
            publishStateLocked()
        }
        return ok
    }

    fun hasPrivilegedAccess(context: Context? = appContext): Boolean {
        if (context != null) init(context)
        if (!isShizukuRunning() && context != null) {
            tryRequestBinder(context)
        }
        if (isShizukuRunning() && isShizukuGranted(context)) {
            return true
        }
        if (rootProbeCache == true) {
            return true
        }
        // Only auto-probe `su` if a real `su` binary exists on non-test Android environments
        if (rootProbeCache == null && isSuBinaryPresent()) {
            return probeRootAccess(forceReprobe = false)
        }
        return false
    }

    /**
     * One-tap UI action for "Connect Shizuku":
     * - If Shizuku is running -> requests permission (or confirms connected).
     * - Else if Root `su` is available -> probes & enables Root access.
     * - Else if Shizuku Manager app is installed -> launches Shizuku so user can tap Start.
     * - Else -> opens https://shizuku.rikka.app/ so user can install Shizuku.
     */
    fun connectOrOpenShizuku(
        context: Context,
        onStatusMessage: (String) -> Unit,
    ) {
        init(context)
        tryRequestBinder(context)
        when {
            isShizukuRunning() && isShizukuGranted(context) -> {
                onStatusMessage("Shizuku is connected — privileged App Data access enabled")
            }

            isShizukuRunning() -> {
                val requested = requestShizukuPermission()
                if (requested) {
                    onStatusMessage("Approve permission in the Shizuku dialog")
                } else {
                    openShizukuAppOrSite(context, onStatusMessage)
                }
            }

            isSuBinaryPresent() && probeRootAccess(forceReprobe = true) -> {
                onStatusMessage("Root (su) connected — privileged App Data access enabled")
            }

            else -> {
                openShizukuAppOrSite(context, onStatusMessage)
            }
        }
    }

    private fun openShizukuAppOrSite(context: Context, onStatusMessage: (String) -> Unit) {
        if (isShizukuInstalled(context)) {
            val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PKG)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null && runCatching { context.startActivity(launch); true }.getOrDefault(false)) {
                onStatusMessage("Start Shizuku via Wireless Debugging, then return to Files")
                return
            }
        }
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_WEBSITE_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(web) }
        onStatusMessage("Install Shizuku and enable Wireless Debugging to browse Android/data")
    }

    // --- Shell VFS Operations ---

    private val RESOLVE_PATH_PREAMBLE = """
resolve_p() {
  p="$1"
  if [ -e "${'$'}p" ] || [ -L "${'$'}p" ]; then printf '%s' "${'$'}p"; return; fi
  case "${'$'}p" in
    /storage/emulated/0/*)
      if [ ! -d "/storage/emulated/0/Android" ] && [ -d "/data/media/0/Android" ]; then
        printf '%s' "/data/media/0/${'$'}{p#/storage/emulated/0/}"
        return
      fi
      ;;
  esac
  printf '%s' "${'$'}p"
}
"""

    fun stat(path: String): VfsNode? {
        val norm = PathUtils.normalizeLocalPath(path)
        val script = """
$RESOLVE_PATH_PREAMBLE
t=$(resolve_p "$1")
if [ ! -e "${'$'}t" ] && [ ! -L "${'$'}t" ]; then exit 2; fi
st=$(stat -c '%A|%s|%Y' "${'$'}t" 2>/dev/null) || st="----------|0|0"
link=""
if [ -L "${'$'}t" ]; then link=$(readlink "${'$'}t" 2>/dev/null || true); fi
is_dir=0
has_nomedia=0
if [ -d "${'$'}t" ]; then
  is_dir=1
  [ -e "${'$'}t/.nomedia" ] && has_nomedia=1
fi
printf '%s\0%s\0%s\0%s\0' "${'$'}is_dir" "${'$'}has_nomedia" "${'$'}st" "${'$'}link"
"""
        val res = runCatching { execScript(script, norm) }.getOrNull() ?: return null
        if (res.exitCode != 0) return null
        return PrivilegedShellCodec.parseSingleStat(norm, res.stdout)
    }

    fun exists(path: String): Boolean {
        val norm = PathUtils.normalizeLocalPath(path)
        val script = """
$RESOLVE_PATH_PREAMBLE
t=$(resolve_p "$1")
[ -e "${'$'}t" ] || [ -L "${'$'}t" ]
"""
        val res = runCatching { execScript(script, norm) }.getOrNull() ?: return false
        return res.exitCode == 0
    }

    fun listDirectory(path: String, includeHidden: Boolean): List<VfsNode>? {
        val norm = PathUtils.normalizeLocalPath(path)
        val script = """
$RESOLVE_PATH_PREAMBLE
dir=$(resolve_p "$1")
if [ ! -d "${'$'}dir" ]; then exit 2; fi
for f in "${'$'}dir"/* "${'$'}dir"/.*; do
  name="${'$'}{f##*/}"
  [ "${'$'}name" = "." ] || [ "${'$'}name" = ".." ] && continue
  [ ! -e "${'$'}f" ] && [ ! -L "${'$'}f" ] && continue
  st=$(stat -c '%A|%s|%Y' "${'$'}f" 2>/dev/null) || st="----------|0|0"
  link=""
  if [ -L "${'$'}f" ]; then link=$(readlink "${'$'}f" 2>/dev/null || true); fi
  is_dir=0
  has_nomedia=0
  cnt=-1
  if [ -d "${'$'}f" ]; then
    is_dir=1
    [ -e "${'$'}f/.nomedia" ] && has_nomedia=1
  fi
  printf '%s\0%s\0%s\0%s\0%s\0%s\0' "${'$'}is_dir" "${'$'}has_nomedia" "${'$'}st" "${'$'}cnt" "${'$'}link" "${'$'}name"
done
"""
        val res = runCatching { execScript(script, norm) }.getOrNull() ?: return null
        if (res.exitCode != 0) return null
        return PrivilegedShellCodec.parseDirectoryListing(norm, res.stdout, includeHidden)
    }

    fun readFileBytes(path: String): ByteArray {
        return openInput(path, 0L).use { it.readBytes() }
    }

    fun writeFileBytes(path: String, bytes: ByteArray, append: Boolean = false) {
        openOutput(path, append).use { out ->
            out.write(bytes)
            out.flush()
        }
    }

    fun mkdir(path: String): Boolean {
        val norm = PathUtils.normalizeLocalPath(path)
        val script = """
$RESOLVE_PATH_PREAMBLE
t=$(resolve_p "$1")
mkdir -p "${'$'}t" 2>/dev/null
"""
        val res = runCatching { execScript(script, norm) }.getOrNull() ?: return false
        return res.exitCode == 0
    }

    fun createFile(path: String): Boolean {
        val norm = PathUtils.normalizeLocalPath(path)
        val script = """
$RESOLVE_PATH_PREAMBLE
t=$(resolve_p "$1")
mkdir -p "${'$'}{t%/*}" 2>/dev/null || true
if [ ! -e "${'$'}t" ]; then : > "${'$'}t"; fi
"""
        val res = runCatching { execScript(script, norm) }.getOrNull() ?: return false
        return res.exitCode == 0
    }

    fun delete(path: String, recursive: Boolean = false): Boolean {
        val norm = PathUtils.normalizeLocalPath(path)
        val script = if (recursive) {
            """
$RESOLVE_PATH_PREAMBLE
t=$(resolve_p "$1")
rm -rf "${'$'}t" 2>/dev/null
"""
        } else {
            """
$RESOLVE_PATH_PREAMBLE
t=$(resolve_p "$1")
if [ -L "${'$'}t" ] || [ ! -d "${'$'}t" ]; then
  rm -f "${'$'}t" 2>/dev/null
else
  rmdir "${'$'}t" 2>/dev/null
fi
"""
        }
        val res = runCatching { execScript(script, norm) }.getOrNull() ?: return false
        return res.exitCode == 0
    }

    fun rename(src: String, dst: String): Boolean {
        val normSrc = PathUtils.normalizeLocalPath(src)
        val normDst = PathUtils.normalizeLocalPath(dst)
        val script = """
$RESOLVE_PATH_PREAMBLE
s=$(resolve_p "$1")
d=$(resolve_p "$2")
mv "${'$'}s" "${'$'}d" 2>/dev/null
"""
        val res = runCatching { execScript(script, normSrc, normDst) }.getOrNull() ?: return false
        return res.exitCode == 0
    }

    fun setLastModified(path: String, epochMs: Long): Boolean {
        val norm = PathUtils.normalizeLocalPath(path)
        val epochSec = (epochMs / 1000L).coerceAtLeast(0L).toString()
        val script = """
$RESOLVE_PATH_PREAMBLE
t=$(resolve_p "$1")
touch -d "@$2" "${'$'}t" 2>/dev/null
"""
        val res = runCatching { execScript(script, norm, epochSec) }.getOrNull() ?: return false
        return res.exitCode == 0
    }

    fun openInput(path: String, offset: Long = 0L): InputStream {
        require(offset >= 0L) { "Negative offset: $offset" }
        val norm = PathUtils.normalizeLocalPath(path)
        if (!exists(norm)) {
            throw FileNotFoundException("Cannot open file for reading: $norm")
        }
        val script = if (offset == 0L) {
            """
$RESOLVE_PATH_PREAMBLE
t=$(resolve_p "$1")
if [ ! -f "${'$'}t" ]; then exit 2; fi
exec cat "${'$'}t" 2>/dev/null
"""
        } else {
            // `tail -c +N` is 1-based byte offset in POSIX / toybox
            val oneBased = offset + 1L
            """
$RESOLVE_PATH_PREAMBLE
t=$(resolve_p "$1")
if [ ! -f "${'$'}t" ]; then exit 2; fi
exec tail -c +$oneBased "${'$'}t" 2>/dev/null
"""
        }
        val proc = spawnShellProcess(script, arrayOf(norm))
        runCatching { proc.stdin.close() }
        runCatching { proc.stderr.close() }
        return object : FilterInputStream(proc.stdout) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                } finally {
                    runCatching { proc.destroy() }
                }
            }
        }
    }

    fun openOutput(path: String, append: Boolean = false): OutputStream {
        val norm = PathUtils.normalizeLocalPath(path)
        val redir = if (append) ">>" else ">"
        val script = """
$RESOLVE_PATH_PREAMBLE
t=$(resolve_p "$1")
mkdir -p "${'$'}{t%/*}" 2>/dev/null || true
cat $redir "${'$'}t" 2>/dev/null
"""
        val proc = spawnShellProcess(script, arrayOf(norm))
        runCatching { proc.stdout.close() }
        runCatching { proc.stderr.close() }
        return object : FilterOutputStream(proc.stdin) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    runCatching { out.flush() }
                    out.close()
                    val code = proc.waitFor()
                    if (code != 0) {
                        throw IOException("Privileged write failed ($code) for $norm")
                    }
                } finally {
                    runCatching { proc.destroy() }
                }
            }
        }
    }

    // --- Process Spawning (Shizuku IRemoteProcess + Root `su -c`) ---

    private data class ShellExecResult(
        val exitCode: Int,
        val stdout: ByteArray,
    )

    private interface ManagedShellProcess {
        val stdin: OutputStream
        val stdout: InputStream
        val stderr: InputStream
        fun waitFor(): Int
        fun destroy()
    }

    private fun execScript(script: String, vararg args: String): ShellExecResult {
        val proc = spawnShellProcess(script, arrayOf(*args))
        return try {
            runCatching { proc.stdin.close() }
            runCatching { proc.stderr.close() }
            val outBytes = proc.stdout.use { it.readBytes() }
            val exit = proc.waitFor()
            ShellExecResult(exit, outBytes)
        } finally {
            runCatching { proc.destroy() }
        }
    }

    private fun spawnShellProcess(script: String, args: Array<String>): ManagedShellProcess {
        val binder = shizukuBinder?.takeIf { it.pingBinder() }
        if (binder != null && isShizukuGranted()) {
            return spawnShizukuProcess(binder, script, args)
        }
        if (rootProbeCache == true || (isSuBinaryPresent() && probeRootAccess())) {
            return spawnRootProcess(script, args)
        }
        throw IOException("Privileged shell access (Shizuku or Root) is not active")
    }

    private fun spawnShizukuProcess(
        serviceBinder: IBinder,
        script: String,
        args: Array<String>,
    ): ManagedShellProcess {
        // Build exact argv: ["/system/bin/sh", "-c", script, "sh", *args]
        val argv = ArrayList<String>(4 + args.size).apply {
            add("/system/bin/sh")
            add("-c")
            add(script)
            add("sh")
            addAll(args)
        }.toTypedArray()

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val remoteProcBinder: IBinder = try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE)
            data.writeStringArray(argv)
            data.writeStringArray(null)
            data.writeString(null)
            if (!serviceBinder.transact(TX_NEW_PROCESS, data, reply, 0)) {
                throw IOException("IShizukuService.newProcess transact failed")
            }
            reply.readException()
            reply.readStrongBinder() ?: throw IOException("IShizukuService.newProcess returned null binder")
        } finally {
            reply.recycle()
            data.recycle()
        }

        val stdinPfd = queryRemoteProcessFd(remoteProcBinder, TX_PROC_GET_OUTPUT_STREAM)
        val stdoutPfd = queryRemoteProcessFd(remoteProcBinder, TX_PROC_GET_INPUT_STREAM)
        val stderrPfd = queryRemoteProcessFd(remoteProcBinder, TX_PROC_GET_ERROR_STREAM)

        return object : ManagedShellProcess {
            override val stdin: OutputStream = stdinPfd?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
                ?: object : OutputStream() {
                    override fun write(b: Int) = Unit
                }
            override val stdout: InputStream = stdoutPfd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
                ?: ByteArrayInputStream(ByteArray(0))
            override val stderr: InputStream = stderrPfd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
                ?: ByteArrayInputStream(ByteArray(0))

            override fun waitFor(): Int {
                val d = Parcel.obtain()
                val r = Parcel.obtain()
                return try {
                    d.writeInterfaceToken(DESCRIPTOR_REMOTE_PROCESS)
                    remoteProcBinder.transact(TX_PROC_WAIT_FOR, d, r, 0)
                    r.readException()
                    r.readInt()
                } finally {
                    r.recycle()
                    d.recycle()
                }
            }

            override fun destroy() {
                val d = Parcel.obtain()
                val r = Parcel.obtain()
                try {
                    d.writeInterfaceToken(DESCRIPTOR_REMOTE_PROCESS)
                    runCatching { remoteProcBinder.transact(TX_PROC_DESTROY, d, r, 0) }
                } finally {
                    r.recycle()
                    d.recycle()
                }
            }
        }
    }

    private fun spawnRootProcess(script: String, args: Array<String>): ManagedShellProcess {
        val cmdLine = buildString {
            append("/system/bin/sh -c ")
            append(PrivilegedShellCodec.shellQuote(script))
            append(" sh")
            for (arg in args) {
                append(' ')
                append(PrivilegedShellCodec.shellQuote(arg))
            }
        }
        val proc = ProcessBuilder("su", "-c", cmdLine).start()
        return object : ManagedShellProcess {
            override val stdin: OutputStream = proc.outputStream
            override val stdout: InputStream = proc.inputStream
            override val stderr: InputStream = proc.errorStream
            override fun waitFor(): Int = proc.waitFor()
            override fun destroy() = proc.destroy()
        }
    }

    private fun queryRemoteProcessFd(procBinder: IBinder, txCode: Int): ParcelFileDescriptor? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_REMOTE_PROCESS)
            if (!procBinder.transact(txCode, data, reply, 0)) return null
            reply.readException()
            if (reply.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(reply) else null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun attachApplicationV13(binder: IBinder, packageName: String): Boolean {
        val args = Bundle().apply {
            putInt(KEY_ATTACH_API_VERSION, SERVER_API_VERSION)
            putString(KEY_ATTACH_PACKAGE_NAME, packageName)
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE)
            data.writeStrongBinder(applicationCallbackBinder)
            data.writeInt(1)
            args.writeToParcel(data, 0)
            val ok = binder.transact(TX_ATTACH_APPLICATION_V13, data, reply, 0)
            reply.readException()
            ok
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun attachApplicationV11(binder: IBinder, packageName: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE)
            data.writeStrongBinder(applicationCallbackBinder)
            data.writeString(packageName)
            val ok = binder.transact(TX_ATTACH_APPLICATION_V11, data, reply, 0)
            reply.readException()
            ok
        } catch (_: Throwable) {
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun queryRemoteUid(binder: IBinder): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE)
            if (!binder.transact(TX_GET_UID, data, reply, 0)) return -1
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun queryRemoteSelfPermission(binder: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE)
            if (!binder.transact(TX_CHECK_SELF_PERMISSION, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun queryRemotePermission(binder: IBinder, permission: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE)
            data.writeString(permission)
            if (!binder.transact(TX_CHECK_PERMISSION, data, reply, 0)) return false
            reply.readException()
            reply.readInt() == PackageManager.PERMISSION_GRANTED
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun publishStateLocked() {
        val running = shizukuBinder?.pingBinder() == true
        _state.value = PrivilegedAccessState(
            shizukuRunning = running,
            shizukuGranted = running && shizukuGrantedCached,
            shizukuUid = if (running) shizukuUidCached else -1,
            rootAvailable = rootProbeCache == true,
        )
    }
}
