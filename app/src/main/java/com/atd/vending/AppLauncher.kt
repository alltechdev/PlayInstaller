// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.app.Activity
import android.widget.Toast

internal fun Activity.openInstalledApp(packageName: String) {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    if (launch == null) {
        Toast.makeText(this, "No launchable activity.", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { startActivity(launch) }.onFailure {
        Toast.makeText(this, "Could not open app: ${it.message}", Toast.LENGTH_SHORT).show()
    }
}
