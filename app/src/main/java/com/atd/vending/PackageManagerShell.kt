// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

object PmParser {
    fun sessionId(output: String): Long? {
        val bracketed = Regex("\\[(\\d+)]").findAll(output).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
        val candidates = if (bracketed.isNotEmpty()) bracketed else output.lineSequence()
            .mapNotNull { it.trim().toLongOrNull() }.toList()
        return candidates.singleOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
    }
    fun succeeded(result: ShellResult): Boolean = result.ok &&
        !Regex("(?im)(^\\s*(failure|error|exception)\\b|INSTALL_FAILED_|SecurityException|IllegalArgumentException)").containsMatchIn(result.output)
    fun uid(output: String, name: String): Int? = output.lineSequence().mapNotNull {
        Regex("^package:${Regex.escape(name)}\\s+uid:(\\d+)\\s*$").matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull()
    }.singleOrNull()
    fun field(dump: String, field: String): String? =
        Regex("(?m)(?:^|\\s)${Regex.escape(field)}=([^\\s,}]+)").find(dump)?.groupValues?.get(1)?.takeUnless { it == "null" }
    fun validPackage(name: String): Boolean = name.length <= 255 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*").matches(name)
}

class PackageManagerShell(private val root: CommandShell, private val log: (String) -> Unit) {
    fun command(vararg args: String): ShellResult {
        log("$ ${ShellEscaper.command(*args)}")
        val result = root.execute(*args)
        log("${result.output}\n[exit ${result.exitCode}]")
        return result
    }
    fun checkCompatibility() {
        val help = command("pm", "help")
        if (!help.ok || !listOf("install-create", "install-write", "install-commit", "--user").all { it in help.output }) {
            throw InstallerException(ErrorKind.CommandFailed, "This device does not advertise the required Package Manager session/User 0 commands.\n${help.output}")
        }
    }
    /** Both shell layers quote arguments; verify identity in each child before invoking pm. */
    private fun asInstaller(uid: Int, vararg args: String): ShellResult {
        require(uid > 0) { "A non-root installer UID must be resolved from an installed package." }
        val expected = ShellEscaper.quote(uid.toString())
        val script = "actual_uid=\$(id -u) || exit 126; " +
            "if [ \"\$actual_uid\" != $expected ]; then " +
            "printf 'Installer UID mismatch: expected %s, got %s\\n' $expected \"\$actual_uid\" >&2; exit 126; fi; " +
            "exec ${ShellEscaper.command(*args)}"
        return command("su", uid.toString(), "-c", script)
    }
    fun verifyInstallerUid(uid: Int) {
        val result = asInstaller(uid, "id", "-u")
        if (!result.ok || result.stdout.trim() != uid.toString()) {
            throw InstallerException(ErrorKind.CommandFailed,
                "Cannot execute as the dynamically resolved installer UID $uid. This su implementation must support su UID -c COMMAND. No root-caller fallback was attempted.\n${result.output}")
        }
    }
    fun createInstallSession(installerPackage: String, size: Long, installerUid: Int, allowDowngrade: Boolean): Long {
        val flags = if (allowDowngrade) arrayOf("-r", "-d") else arrayOf("-r")
        val r = asInstaller(installerUid, "pm", "install-create", *flags, "-i", installerPackage, "--user", "0", "-S", size.toString())
        if (!PmParser.succeeded(r)) throw InstallerException(ErrorKind.SessionCreationFailed, r.output)
        return PmParser.sessionId(r.stdout) ?: throw InstallerException(ErrorKind.SessionCreationFailed,
            "Android returned no unambiguous session ID. No write/commit attempted; an unidentified session may require manual inspection.\n${r.output}")
    }
    fun writeInstallSession(id: Long, name: String, path: String, size: Long) = command("pm", "install-write", "-S", size.toString(), id.toString(), name, path)
    fun commitInstallSession(id: Long, installerUid: Int) = asInstaller(installerUid, "pm", "install-commit", id.toString())
    fun abandonInstallSession(id: Long, installerUid: Int? = null) =
        if (installerUid != null) asInstaller(installerUid, "pm", "install-abandon", id.toString())
        else command("pm", "install-abandon", id.toString())
    fun uninstall(name: String) = command("pm", "uninstall", "--user", "0", name)
    fun dump(name: String) = command("dumpsys", "package", name)
    fun listPackage(name: String) = command("pm", "list", "packages", "-U", "--user", "0", name)
}
