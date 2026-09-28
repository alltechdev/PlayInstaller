// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.content.Context
import java.util.UUID

data class InstallResult(val success: Boolean, val packageName: String, val sessionId: Long?,
    val metadata: PackageMetadata?, val error: String?, val warnings: List<String>)

private const val STAGING_DIR = "/data/local/tmp"
private const val OBB_ROOT = "/storage/emulated/0/Android/obb"
private const val UUID_PATTERN = "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"

class InstallController internal constructor(private val openRoot: () -> CommandShell,
    private val lookup: (PackageManagerShell) -> PackageLookup, private val log: (String) -> Unit,
    private val progress: (String) -> Unit) {
    constructor(context: Context, log: (String) -> Unit, progress: (String) -> Unit) :
        this({ RootShell().open() }, { InstallerResolver(context, it) }, log, progress)

    fun install(apk: Apk, installer: String, replaceInstalled: Boolean = false): InstallResult {
        var session: Long? = null
        var uninstalled = false
        var sessionOwnerUid: Int? = null
        var committed = false
        var sessionAbandoned = false
        var completed = false
        val staged = mutableSetOf<String>()
        var error: String? = null
        var metadata: PackageMetadata? = null
        var root: CommandShell? = null
        val warnings = mutableListOf<String>()
        val operationId = UUID.randomUUID().toString()
        try {
            progress("Preparing…")
            val files = apk.parts.map { it.file } + apk.expansions.map { it.file }
            if (apk.parts.isEmpty() || files.any { !it.isFile }) throw InstallerException(ErrorKind.ApkNotFound, "Select the file again; its private copy is missing.")
            if (files.any { !it.canRead() || it.length() == 0L }) throw InstallerException(ErrorKind.ApkUnreadable, "A selected file is empty or cannot be read.")
            root = openRoot()
            val pm = PackageManagerShell(root, log)
            val identity = lookup(pm).resolve(installer)
            log(identity.description)
            if (!identity.installed) throw InstallerException(ErrorKind.InstallerPackageNotFound,
                "Installer package is not installed for User 0. Both installer and initiator attribution require an installed package with a resolvable UID.")
            val uid = identity.uid?.takeIf { it > 0 } ?: throw InstallerException(ErrorKind.CommandFailed,
                "Cannot resolve a non-root User 0 UID for $installer. No install session was created.")
            sessionOwnerUid = uid
            pm.verifyInstallerUid(uid)
            log("Session creator and committer: $installer (resolved UID $uid). Staging and session writing: root.")
            pm.checkCompatibility()
            sweepStale(pm)
            if (replaceInstalled) {
                progress("Uninstalling the installed version…")
                checked(pm.uninstall(apk.packageName), ErrorKind.CommandFailed)
                uninstalled = true
            }
            val paths = apk.parts.mapIndexed { index, part ->
                progress("Staging APK ${index + 1}/${apk.parts.size}…")
                val path = "$STAGING_DIR/root-installer-$operationId-$index.apk"
                staged.add(path) // Include partial copies in cleanup.
                checked(pm.command("cp", part.file.path, path), ErrorKind.CommandFailed)
                checked(pm.command("chmod", "0644", path), ErrorKind.CommandFailed)
                path
            }
            progress("Creating session…")
            session = pm.createInstallSession(installer, apk.size, uid)
            apk.parts.forEachIndexed { index, part ->
                progress("Writing APK ${index + 1}/${apk.parts.size}…")
                checked(pm.writeInstallSession(session, part.sessionName, paths[index], part.file.length()), ErrorKind.SessionWriteFailed)
            }
            progress("Committing…")
            checked(pm.commitInstallSession(session, uid), ErrorKind.SessionCommitFailed)
            committed = true
            progress("Inspecting recorded metadata…")
            try {
                val recorded = lookup(pm).readPackageInfo(apk.packageName)
                metadata = recorded
                if (recorded.installer != installer || recorded.initiating != installer) {
                    warnings.add("Attribution not confirmed: requested installer and initiating package $installer; Android reports installer=${recorded.installer ?: "Unknown"}, initiating=${recorded.initiating ?: "Unknown"}. The APK was installed, but both requested attribution fields were not confirmed.")
                }
            }
            catch (e: Exception) { warnings.add("Installed, but metadata query failed: ${e.message}") }
            if (apk.expansions.isNotEmpty()) {
                progress("Copying expansion data…")
                val destination = "$OBB_ROOT/${apk.packageName}"
                checked(pm.command("mkdir", "-p", destination), ErrorKind.CommandFailed)
                apk.expansions.forEachIndexed { index, expansion ->
                    val temporary = "$destination/.root-installer-$operationId-$index.tmp"
                    staged.add(temporary)
                    checked(pm.command("cp", expansion.file.path, temporary), ErrorKind.CommandFailed)
                    checked(pm.command("chmod", "0644", temporary), ErrorKind.CommandFailed)
                    checked(pm.command("mv", "-f", temporary, "$destination/${expansion.name}"), ErrorKind.CommandFailed)
                    staged.remove(temporary)
                }
            }
            completed = true
        } catch (e: Exception) {
            error = "${(e as? InstallerException)?.kind ?: ErrorKind.CommandFailed}: ${e.message}\n${friendlyError(e.message.orEmpty())}"
            if (committed) error = "The APKs were installed, but expansion data could not be fully copied. The app remains installed; retry the archive.\n$error"
            else if (uninstalled) error = "The installed version was uninstalled, but this version failed to install. Its data is gone; select the file again to retry.\n$error"
            log(error)
        } finally {
            progress("Cleaning up…")
            // A timed-out shell is unusable. Retry cleanup once in a fresh, verified root shell.
            fun cleanup(shell: CommandShell): Boolean {
                val pm = PackageManagerShell(shell, log)
                var ok = true
                if (session != null && !committed && !sessionAbandoned) {
                    var abandoned = runCatching { PmParser.succeeded(pm.abandonInstallSession(session, sessionOwnerUid)) }
                        .onFailure { log("Abandon failed: ${it.message}") }.getOrDefault(false)
                    if (!abandoned) {
                        log("Retrying session abandonment as root.")
                        abandoned = runCatching { PmParser.succeeded(pm.abandonInstallSession(session)) }
                            .onFailure { log("Root abandon failed: ${it.message}") }.getOrDefault(false)
                    }
                    sessionAbandoned = abandoned
                    if (!abandoned) ok = false
                }
                for (path in staged.toList()) {
                    val removed = runCatching { pm.command("rm", "-f", path).ok }
                        .onFailure { log("Temporary-file cleanup failed: ${it.message}") }.getOrDefault(false)
                    if (!removed) ok = false else staged.remove(path)
                }
                return ok
            }
            if (staged.isNotEmpty() || (session != null && !committed)) {
                var clean = runCatching { root?.let { cleanup(it) } ?: false }.getOrDefault(false)
                if (!clean) {
                    root?.close()
                    clean = runCatching { openRoot().use { cleanup(it) } }.getOrDefault(false)
                }
                if (!clean) warnings.add("CleanupFailed: inspect session ${session ?: "Unknown"} and ${staged.joinToString()}. Cleanup could not be confirmed.")
            }
            root?.close()
            if (!apk.delete()) warnings.add("CleanupFailed: private APK cache could not be removed.")
        }
        warnings.forEach(log)
        return InstallResult(completed, apk.packageName, session, metadata, error, warnings)
    }

    fun uninstall(packageName: String) {
        require(PmParser.validPackage(packageName))
        openRoot().use { checked(PackageManagerShell(it, log).uninstall(packageName), ErrorKind.CommandFailed) }
    }

    /** Leftovers of a process killed mid-install. Never a glob as root: only exact, validated paths are removed. */
    private fun sweepStale(pm: PackageManagerShell) {
        val stale = listOf(
            listOf("find", STAGING_DIR, "-maxdepth", "1", "-name", "root-installer-*.apk") to
                Regex("${Regex.escape(STAGING_DIR)}/root-installer-$UUID_PATTERN-\\d+\\.apk"),
            listOf("find", OBB_ROOT, "-mindepth", "2", "-maxdepth", "2", "-name", ".root-installer-*.tmp") to
                Regex("${Regex.escape(OBB_ROOT)}/[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*/\\.root-installer-$UUID_PATTERN-\\d+\\.tmp")
        ).flatMap { (find, exact) ->
            val listed = pm.command(*find.toTypedArray())
            if (listed.ok) listed.stdout.lineSequence().map { it.trim() }.filter { exact.matches(it) }.toList() else emptyList()
        }.distinct()
        if (stale.isNotEmpty()) progress("Removing ${stale.size} stale file(s)…")
        for (path in stale) {
            if (!pm.command("rm", "-f", path).ok) log("Stale file could not be removed: $path")
        }
    }

    private fun checked(result: ShellResult, kind: ErrorKind) {
        if (!PmParser.succeeded(result)) throw InstallerException(kind, result.output.ifBlank { "Command exited with ${result.exitCode}." })
    }
}

fun friendlyError(raw: String): String = when {
    "INSTALL_FAILED_VERSION_DOWNGRADE" in raw -> "The installed version is newer. Select a newer APK; downgrade flags are not added automatically."
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in raw -> "The update signature is incompatible. Obtain an APK signed by the original developer."
    "INSTALL_FAILED_INVALID_APK" in raw -> "Android rejected this APK set. Obtain a valid APK or a complete archive with matching base and split APKs."
    "INSTALL_FAILED_INSUFFICIENT_STORAGE" in raw -> "Free device storage and retry."
    "INSTALL_FAILED_USER_RESTRICTED" in raw -> "A device policy restricts installation. Review the policy with the device administrator."
    "Unknown option" in raw || "unknown option" in raw -> "This Android build does not support a required Package Manager option. See the original error above."
    "Permission denied" in raw || "SecurityException" in raw -> "Android or SELinux denied the operation despite su. Review root permissions and the original error; no security policy is changed."
    else -> "Review the original Android error above before retrying."
}
