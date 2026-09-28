// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

enum class QueueStatus(val label: String) {
    Pending("Pending"), Reading("Reading"), Ready("Ready"), Installing("Installing"),
    Installed("Installed"), Failed("Failed"), Skipped("Skipped")
}

data class QueuedFile(val uri: String, val name: String, val status: QueueStatus = QueueStatus.Pending, val detail: String = "")

fun List<QueuedFile>.summary(): String {
    val counts = listOf(QueueStatus.Installed, QueueStatus.Failed, QueueStatus.Skipped)
        .joinToString(", ") { status -> "${count { it.status == status }} ${status.label.lowercase()}" }
    return (listOf(counts, "") + map { "${it.name}: ${it.status.label}${if (it.detail.isEmpty()) "" else " · ${it.detail}"}" }).joinToString("\n")
}

val InstallerUiState.batch: Boolean get() = queue.size > 1
val InstallerUiState.current: QueuedFile? get() = queue.getOrNull(queueIndex)
