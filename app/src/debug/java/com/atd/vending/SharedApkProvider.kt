// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Private debug-only share source. Its separate process has the debug app's Kotlin runtime. */
class SharedApkProvider : ContentProvider() {
    private val reads = AtomicInteger()
    @Volatile private var gate = CountDownLatch(0)

    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("fixture.apk")) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        reads.incrementAndGet()
        if (uri.lastPathSegment == "missing") throw FileNotFoundException("Fixture unavailable")
        val apk = File(requireNotNull(context).applicationInfo.sourceDir)
        if (uri.lastPathSegment != "slow") return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
        val pipe = ParcelFileDescriptor.createPipe()
        val pending = gate
        thread(name = "shared-apk-fixture") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    if (pending.await(20, TimeUnit.SECONDS)) apk.inputStream().use { it.copyTo(output) }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: IOException) {
                // Expected if a test aborts or the target process exits while reading.
                android.util.Log.i("SharedApkProvider", "Reader closed", e)
            }
        }
        return pipe[0]
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        when (method) {
            "reset" -> { gate.countDown(); gate = CountDownLatch(1); reads.set(0) }
            "release" -> gate.countDown()
        }
        return Bundle().apply { putInt("reads", reads.get()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
