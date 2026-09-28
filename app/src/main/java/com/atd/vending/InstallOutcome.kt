// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

const val PLAY_STORE_PACKAGE = "com.android.vending"

enum class InstallOutcome(val title: String, val installed: Boolean, val warning: Boolean = false) {
    Success("Installed successfully", true),
    AttributionUnconfirmed("Installed, Play attribution unconfirmed", true, true),
    WithWarnings("Installed with warnings", true, true),
    Failure("Installation failed", false)
}

/** Payload completion alone is insufficient to claim a successful Play-attributed install. */
val InstallResult.outcome: InstallOutcome
    get() = when {
        !success -> InstallOutcome.Failure
        metadata?.initiating != PLAY_STORE_PACKAGE || metadata.installer != PLAY_STORE_PACKAGE ->
            InstallOutcome.AttributionUnconfirmed
        warnings.isNotEmpty() -> InstallOutcome.WithWarnings
        else -> InstallOutcome.Success
    }

fun InstallerUiState.report(): String = if (log.isEmpty()) result else "$result\n\nCommand log:\n$log"

fun InstallResult.summary(): String = buildString {
    appendLine(outcome.title)
    appendLine()
    appendLine(packageName)
    if (success) {
        appendLine("Installer: ${metadata?.installer ?: "Unknown"}")
        appendLine("Initiating package: ${metadata?.initiating ?: "Unknown"}")
        if (outcome == InstallOutcome.AttributionUnconfirmed) {
            appendLine()
            appendLine("The APK is installed, but Google Play attribution was not confirmed. Both recorded package names must be exactly $PLAY_STORE_PACKAGE.")
        }
    } else {
        appendLine()
        appendLine(error.orEmpty())
    }
    if (warnings.isNotEmpty()) {
        appendLine()
        append(warnings.joinToString("\n"))
    }
}.trim()
