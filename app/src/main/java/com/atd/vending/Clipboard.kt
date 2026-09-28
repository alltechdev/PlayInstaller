// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast

internal fun Activity.copyReport(state: InstallerUiState) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(
        ClipData.newPlainText("Installation result", state.report()))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Result copied", Toast.LENGTH_SHORT).show()
}
