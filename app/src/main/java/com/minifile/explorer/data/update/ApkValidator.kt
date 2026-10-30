package com.minifile.explorer.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.minifile.explorer.core.update.UpdateErrorCode
import com.minifile.explorer.core.update.UpdateException
import java.io.File
import java.security.MessageDigest

object ApkValidator {

    /**
     * Strictly validates a downloaded update APK before atomic rename (`ARCHITECTURE.md` §11.1):
     * 1. `getPackageArchiveInfo` succeeds
     * 2. `packageName == context.packageName`
     * 3. `versionCode > installedVersionCode`
     * 4. Signing certificate set matches installed app (API 28+ `GET_SIGNING_CERTIFICATES` with
     *    API 28–29 `GET_SIGNATURES` archive fallback; fails closed if empty or mismatched).
     */
    fun validate(context: Context, apkFile: File) {
        val pm = context.packageManager
        val ownPkg = context.packageName
        val installedInfo = getInstalledInfo(pm, ownPkg)
        val installedVersionCode = versionCodeOf(installedInfo)

        val archiveInfo = getArchiveInfo(pm, apkFile.absolutePath)
            ?: throw UpdateException(
                UpdateErrorCode.VERIFY_FAILED,
                "Not a valid Android APK package.",
            )

        if (archiveInfo.packageName != ownPkg) {
            throw UpdateException(
                UpdateErrorCode.VERIFY_FAILED,
                "Package mismatch: expected $ownPkg, got ${archiveInfo.packageName}.",
            )
        }

        val candidateVersionCode = versionCodeOf(archiveInfo)
        if (candidateVersionCode <= installedVersionCode) {
            throw UpdateException(
                UpdateErrorCode.VERIFY_FAILED,
                "Downloaded APK versionCode ($candidateVersionCode) is not newer than installed ($installedVersionCode).",
            )
        }

        val archiveCertDigests = extractArchiveCertDigests(pm, apkFile.absolutePath, archiveInfo)
        val installedCertDigests = extractInstalledCertDigests(pm, ownPkg, installedInfo)

        if (archiveCertDigests.isEmpty() || installedCertDigests.isEmpty()) {
            throw UpdateException(
                UpdateErrorCode.VERIFY_FAILED,
                "Could not verify APK signing certificates.",
            )
        }
        if (archiveCertDigests != installedCertDigests) {
            throw UpdateException(
                UpdateErrorCode.VERIFY_FAILED,
                "The downloaded update is not signed by the same key as the installed app.",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledInfo(pm: PackageManager, pkg: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
    }

    @Suppress("DEPRECATION")
    private fun getArchiveInfo(pm: PackageManager, path: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractArchiveCertDigests(
        pm: PackageManager,
        path: String,
        primaryInfo: PackageInfo,
    ): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = primaryInfo.signingInfo
            val signers: Array<Signature>? = signing?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
            if (!signers.isNullOrEmpty()) {
                return digestSignatures(signers)
            }
            // API 28-29 quirk: signingInfo can be null on getPackageArchiveInfo; retry with GET_SIGNATURES
            val legacy = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
            val legacySigs = legacy?.signatures
            if (!legacySigs.isNullOrEmpty()) {
                return digestSignatures(legacySigs)
            }
            return emptySet()
        } else {
            val sigs = primaryInfo.signatures
            return if (sigs.isNullOrEmpty()) emptySet() else digestSignatures(sigs)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractInstalledCertDigests(
        pm: PackageManager,
        pkg: String,
        primaryInfo: PackageInfo,
    ): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = primaryInfo.signingInfo
            val signers: Array<Signature>? = signing?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
            if (!signers.isNullOrEmpty()) {
                return digestSignatures(signers)
            }
            val legacy = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            val legacySigs = legacy.signatures
            if (!legacySigs.isNullOrEmpty()) {
                return digestSignatures(legacySigs)
            }
            return emptySet()
        } else {
            val sigs = primaryInfo.signatures
            return if (sigs.isNullOrEmpty()) emptySet() else digestSignatures(sigs)
        }
    }

    private fun digestSignatures(signatures: Array<Signature>): Set<String> {
        val out = HashSet<String>(signatures.size)
        for (sig in signatures) {
            val sha = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            val sb = StringBuilder(sha.size * 2)
            for (b in sha) {
                sb.append(String.format("%02x", b.toInt() and 0xFF))
            }
            out.add(sb.toString())
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
}
