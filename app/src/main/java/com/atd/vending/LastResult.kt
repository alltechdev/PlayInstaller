// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import java.io.File

/** The last install result and log, kept across restarts so a crash never loses a report worth copying. */
object LastResult {
    private const val SEPARATOR = "\n\u0000\n"

    fun encode(state: InstallerUiState): String =
        listOf(state.outcome?.name.orEmpty(), state.isError.toString(), state.result, state.log).joinToString(SEPARATOR)

    fun decode(text: String): InstallerUiState? {
        val parts = text.split(SEPARATOR, limit = 4)
        if (parts.size != 4 || parts[2].isEmpty()) return null
        val outcome = parts[0].takeIf { it.isNotEmpty() }?.let { name -> InstallOutcome.entries.firstOrNull { it.name == name } ?: return null }
        return InstallerUiState(result = parts[2], isError = parts[1].toBoolean(), outcome = outcome, log = parts[3])
    }

    fun file(dir: File) = File(dir, "last-result.txt")
    fun save(dir: File, state: InstallerUiState) = runCatching { file(dir).writeText(encode(state)) }
    fun load(dir: File): InstallerUiState? = runCatching { file(dir).takeIf { it.isFile }?.readText()?.let(::decode) }.getOrNull()
    fun clear(dir: File) = runCatching { file(dir).delete() }
}
