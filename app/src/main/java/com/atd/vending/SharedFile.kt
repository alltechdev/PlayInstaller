// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.content.Intent
import android.net.Uri
import android.os.Build

internal fun requireSharedFile(scheme: String?, authority: String?, count: Int) {
    require(count in 0..1) { "Share one APK or archive at a time." }
    require(scheme == "content" && !authority.isNullOrEmpty()) {
        "Open or share the file using a file manager that grants access to a content URI, or use the file picker."
    }
}

@Suppress("DEPRECATION")
internal fun Intent.sharedFileUri(): Uri {
    require(action == Intent.ACTION_VIEW || action == Intent.ACTION_SEND) { "Unsupported file action." }
    val uri = if (action == Intent.ACTION_VIEW) data else {
        val stream = if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri
        stream ?: clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
    }
    requireSharedFile(uri?.scheme, uri?.authority, clipData?.itemCount ?: 0)
    return requireNotNull(uri)
}
