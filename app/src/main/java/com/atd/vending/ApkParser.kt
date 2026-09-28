// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import java.io.File
import android.graphics.Bitmap

data class ApkPart(val file: File, val sessionName: String)
data class Expansion(val file: File, val name: String)
data class Apk(val directory: File, val parts: List<ApkPart>, val expansions: List<Expansion>,
    val displayName: String, val packageName: String,
    val label: String, val version: String,
    val icon: Bitmap? = null, val versionCode: Long = 0) {
    val size: Long get() = parts.sumOf { it.file.length() }
    fun delete(): Boolean = directory.deleteRecursively()
}
