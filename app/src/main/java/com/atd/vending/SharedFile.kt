// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.content.Intent
import android.net.Uri
import android.os.Build

internal fun requireSharedFile(scheme: String?, authority: String?) {
    require(scheme == "content" && !authority.isNullOrEmpty()) {
        "Open or share the file using a file manager that grants access to a content URI, or use the file picker."
    }
}

@Suppress("DEPRECATION")
internal fun Intent.sharedFileUris(): List<Uri> {
    val uris: List<Uri?> = when (action) {
        Intent.ACTION_VIEW -> listOf(data)
        Intent.ACTION_SEND -> listOf(
            (if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
                ?: clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri)
        Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)?.map { it as? Uri })
            ?: clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }.orEmpty()
        else -> throw IllegalArgumentException("Unsupported file action.")
    }
    require(uris.isNotEmpty()) { "No file was shared." }
    require(uris.size <= 64) { "Share at most 64 files at a time." }
    uris.forEach { requireSharedFile(it?.scheme, it?.authority) }
    return uris.map { requireNotNull(it) }
}
