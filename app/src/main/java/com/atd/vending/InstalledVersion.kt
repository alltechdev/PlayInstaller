// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build

data class InstalledNote(val text: String, val downgrade: Boolean)

@Suppress("DEPRECATION")
val PackageInfo.code: Long get() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

fun installedNote(installedCode: Long, installedName: String?, code: Long): InstalledNote {
    val name = installedName ?: installedCode.toString()
    return when {
        installedCode > code -> InstalledNote("Installed version $name is newer. Installation will fail.", true)
        installedCode == code -> InstalledNote("Version $name is already installed and will be reinstalled.", false)
        else -> InstalledNote("Update from version $name.", false)
    }
}

/** Null when the package is absent or invisible; the manifest queries only launchable apps. */
fun Context.installedNote(packageName: String, code: Long): InstalledNote? =
    runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()?.let { installedNote(it.code, it.versionName, code) }
