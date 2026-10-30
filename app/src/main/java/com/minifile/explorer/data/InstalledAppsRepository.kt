package com.minifile.explorer.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val isSystemApp: Boolean,
    val apkSizeBytes: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val internalDataDir: String,
    val externalDataPath: String,
    val obbPath: String,
    val mediaPath: String,
) {
    val hasSplits: Boolean get() = splitSourceDirs.isNotEmpty()
}

class InstalledAppsRepository(
    context: Context,
    private val fs: FileSystem,
) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager

    @Suppress("DEPRECATION")
    suspend fun loadInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        val apps = pm.getInstalledApplications(0)
        val out = ArrayList<InstalledAppInfo>(apps.size)
        for (app in apps) {
            val pkg = app.packageName ?: continue
            val pkgInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg)
            val sourceDir = app.sourceDir.orEmpty()
            val splits = app.splitSourceDirs?.toList().orEmpty()
            val baseSize = sourceDir.takeIf { it.isNotEmpty() }?.let { File(it).length() } ?: 0L
            val splitSize = splits.sumOf { File(it).length() }

            val vCode = if (pkgInfo != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else pkgInfo.versionCode.toLong()
            } else {
                0L
            }
            val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

            out.add(
                InstalledAppInfo(
                    packageName = pkg,
                    label = label,
                    versionName = pkgInfo?.versionName ?: "—",
                    versionCode = vCode,
                    minSdk = app.minSdkVersion,
                    targetSdk = app.targetSdkVersion,
                    isSystemApp = isSys,
                    apkSizeBytes = baseSize + splitSize,
                    firstInstallTime = pkgInfo?.firstInstallTime ?: 0L,
                    lastUpdateTime = pkgInfo?.lastUpdateTime ?: 0L,
                    sourceDir = sourceDir,
                    splitSourceDirs = splits,
                    internalDataDir = app.dataDir.orEmpty(),
                    externalDataPath = "$primaryRoot/Android/data/$pkg",
                    obbPath = "$primaryRoot/Android/obb/$pkg",
                    mediaPath = "$primaryRoot/Android/media/$pkg",
                ),
            )
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        out
    }

    suspend fun computeExternalDirSize(localPath: String): Long? = withContext(Dispatchers.IO) {
        val dir = File(localPath)
        if (!dir.exists() || !dir.canRead()) return@withContext null
        fun sum(f: File, depth: Int): Long {
            if (depth > 16) return 0L
            if (f.isFile) return f.length()
            val kids = f.listFiles() ?: return 0L
            var acc = 0L
            for (k in kids) {
                acc += sum(k, depth + 1)
            }
            return acc
        }
        runCatching { sum(dir, 0) }.getOrNull()
    }

    suspend fun exportApk(
        app: InstalledAppInfo,
        dstDir: VfsPath,
        sink: ProgressSink = ProgressSink.NOOP,
    ): VfsPath = withContext(Dispatchers.IO) {
        val baseFile = File(app.sourceDir)
        if (!baseFile.isFile || !baseFile.canRead()) {
            throw IOException("Cannot read APK at ${app.sourceDir}")
        }
        val safeVersion = app.versionName.replace(Regex("[^0-9A-Za-z._-]"), "_").ifEmpty { "v${app.versionCode}" }
        val existingNames = runCatching { fs.list(dstDir, includeHidden = true).map { it.name }.toSet() }.getOrDefault(emptySet())

        if (!app.hasSplits) {
            val desired = "${app.packageName}-$safeVersion.apk"
            val finalName = PathUtils.uniqueName(desired, existingNames)
            val created = fs.createFile(dstDir, finalName, "application/vnd.android.package-archive")
            val total = baseFile.length()
            var copied = 0L
            val buf = ByteArray(64 * 1024)
            baseFile.inputStream().use { ins ->
                fs.openOutput(created, append = false).use { out ->
                    while (true) {
                        if (sink.isCancelled()) {
                            runCatching { fs.delete(created) }
                            throw IOException("APK export cancelled")
                        }
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            out.write(buf, 0, n)
                            copied += n
                            sink.onProgress(
                                OperationProgress(
                                    id = "export-apk",
                                    kind = OperationKind.EXPORT_APK,
                                    title = "Exporting ${app.label}",
                                    currentItem = finalName,
                                    processedItems = 0,
                                    totalItems = 1,
                                    processedBytes = copied,
                                    totalBytes = total,
                                ),
                            )
                        }
                    }
                    out.flush()
                }
            }
            created
        } else {
            val allFiles = listOf(baseFile) + app.splitSourceDirs.map { File(it) }.filter { it.isFile && it.canRead() }
            val desired = "${app.packageName}-$safeVersion.apks"
            val finalName = PathUtils.uniqueName(desired, existingNames)
            val created = fs.createFile(dstDir, finalName, "application/zip")
            val total = allFiles.sumOf { it.length() }
            var copied = 0L
            val buf = ByteArray(64 * 1024)
            ZipOutputStream(fs.openOutput(created, append = false), Charsets.UTF_8).use { zos ->
                for ((idx, apkPart) in allFiles.withIndex()) {
                    val entryName = if (idx == 0) "base.apk" else apkPart.name
                    val entry = ZipEntry(entryName).apply { time = apkPart.lastModified() }
                    zos.putNextEntry(entry)
                    apkPart.inputStream().use { ins ->
                        while (true) {
                            if (sink.isCancelled()) {
                                runCatching { fs.delete(created) }
                                throw IOException("APK bundle export cancelled")
                            }
                            val n = ins.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                zos.write(buf, 0, n)
                                copied += n
                                sink.onProgress(
                                    OperationProgress(
                                        id = "export-apk",
                                        kind = OperationKind.EXPORT_APK,
                                        title = "Exporting ${app.label}",
                                        currentItem = entryName,
                                        processedItems = idx,
                                        totalItems = allFiles.size,
                                        processedBytes = copied,
                                        totalBytes = total,
                                    ),
                                )
                            }
                        }
                    }
                    zos.closeEntry()
                }
                zos.finish()
            }
            created
        }
    }
}
