// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

data class InstalledNote(val text: String, val downgrade: Boolean = false, val signatureMismatch: Boolean = false) {
    fun blocks(allowDowngrade: Boolean) = signatureMismatch || (downgrade && !allowDowngrade)
}

@Suppress("DEPRECATION")
val PackageInfo.code: Long get() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
val SIGNING_FLAG: Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
val PackageInfo.signers: Set<String>
    get() = (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures).orEmpty()
        .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()

fun installedNote(installedCode: Long, installedName: String?, code: Long, sameSigner: Boolean?): InstalledNote {
    val name = installedName ?: installedCode.toString()
    return when {
        sameSigner == false -> InstalledNote("Installed version $name is signed by a different key. Uninstall it first.", signatureMismatch = true)
        installedCode > code -> InstalledNote("Installed version $name is newer. Allow the downgrade or uninstall it first; app data may not survive a downgrade.", downgrade = true)
        installedCode == code -> InstalledNote("Version $name is already installed and will be reinstalled.")
        else -> InstalledNote("Update from version $name.")
    }
}

/** Null when the package is absent or invisible; the manifest queries only launchable apps. */
fun Context.installedNote(packageName: String, code: Long, signers: Set<String>): InstalledNote? =
    runCatching { packageManager.getPackageInfo(packageName, SIGNING_FLAG) }.getOrNull()?.let {
        val installed = it.signers
        installedNote(it.code, it.versionName, code, if (signers.isEmpty() || installed.isEmpty()) null else signers == installed)
    }
