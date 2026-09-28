// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.UserHandle

data class InstallerIdentity(val name: String, val installed: Boolean, val uid: Int?, val label: String?) {
    val description get() = "$name\nUser 0: ${if (installed) "Installed" else "Not installed"}\nInstaller UID: ${uid ?: "Unknown"}${label?.let { "\n$it" }.orEmpty()}"
}

data class PackageMetadata(val installer: String?, val initiating: String?)

interface PackageLookup {
    fun resolve(name: String): InstallerIdentity
    fun readPackageInfo(name: String): PackageMetadata
}

class InstallerResolver(private val context: Context, private val pm: PackageManagerShell) : PackageLookup {
    private val inUserZero get() = Process.myUserHandle() == UserHandle.getUserHandleForUid(0)

    @Suppress("DEPRECATION")
    override fun resolve(name: String): InstallerIdentity {
        require(PmParser.validPackage(name)) { "Enter a valid package name." }
        // Framework lookup is preferred only when it refers to the target user.
        val app = if (inUserZero) runCatching { context.packageManager.getApplicationInfo(name, 0) }.getOrNull() else null
        if (app != null && app.flags and ApplicationInfo.FLAG_INSTALLED != 0) {
            return InstallerIdentity(name, true, app.uid, app.loadLabel(context.packageManager).toString())
        }
        val listed = pm.listPackage(name)
        if (PmParser.succeeded(listed)) {
            val uid = PmParser.uid(listed.stdout, name)
            if (uid != null) return InstallerIdentity(name, true, uid, null)
            // Some OEMs ignore -U; accept only exact package lines, never substring matches.
            if (listed.stdout.lineSequence().any { it.trim() == "package:$name" }) return InstallerIdentity(name, true, null, null)
            return InstallerIdentity(name, false, null, null)
        }
        // Older/OEM list implementations may not support -U. Keep user selection explicit.
        val fallback = pm.command("pm", "list", "packages", "--user", "0", name)
        if (!PmParser.succeeded(fallback)) throw InstallerException(ErrorKind.CommandFailed, "Cannot verify installer for User 0.\n${fallback.output}")
        val installed = fallback.stdout.lineSequence().any { it.trim() == "package:$name" }
        val dump = if (installed) pm.dump(name) else null
        val uid = dump?.takeIf { it.ok }?.let { PmParser.field(it.stdout, "userId")?.toIntOrNull() }
        return InstallerIdentity(name, installed, uid, null)
    }

    @Suppress("DEPRECATION")
    override fun readPackageInfo(name: String): PackageMetadata {
        val identity = resolve(name)
        val dump = pm.dump(name)
        val text = if (dump.ok) dump.stdout else ""
        // Read all API-30 properties inside the version guard, exposing only strings below.
        val source = if (inUserZero && identity.installed && Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.packageManager.getInstallSourceInfo(name).let {
                    Pair(it.installingPackageName, it.initiatingPackageName)
                }
            }.getOrNull()
        } else null
        val installer = source?.first ?: PmParser.field(text, "installerPackageName")
            ?: if (inUserZero && identity.installed) runCatching { context.packageManager.getInstallerPackageName(name) }.getOrNull() else null
        val initiating = source?.second ?: PmParser.field(text, "initiatingPackageName")
        return PackageMetadata(installer, initiating)
    }
}
