// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.content.Context
import android.content.pm.PackageInfo
import android.annotation.SuppressLint
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.concurrent.thread

class ApkParser(private val context: Context, private val stallTimeoutMs: Long = 30_000,
    private val progress: (copied: Long, total: Long?) -> Unit = { _, _ -> }) {
    private var copiedBytes = 0L
    @Volatile private var input: InputStream? = null
    @Volatile private var aborted = false
    @Volatile private var stalled = false
    @Volatile private var lastRead = 0L

    fun abort() {
        aborted = true
        runCatching { input?.close() }
    }

    fun read(uri: Uri): Apk {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "selected-").toFile()
        try {
            val (name, total) = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    fun column(name: String) = it.getColumnIndex(name).takeIf { index -> index >= 0 && !it.isNull(index) }
                    if (it.moveToFirst()) Pair(column(OpenableColumns.DISPLAY_NAME)?.let(it::getString),
                        column(OpenableColumns.SIZE)?.let(it::getLong)?.takeIf { size -> size > 0 }) else null
                }
            }.getOrNull() ?: Pair(null, null)
            val source = File(directory, "source.apk")
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw InstallerException(ErrorKind.ApkUnreadable, "Cannot open the selected file.")
            input = stream
            lastRead = System.nanoTime()
            val watchdog = thread(name = "read-watchdog", isDaemon = true) {
                try {
                    while (true) {
                        Thread.sleep(500)
                        if (System.nanoTime() - lastRead > stallTimeoutMs * 1_000_000) {
                            stalled = true
                            runCatching { stream.close() }
                            return@thread
                        }
                    }
                } catch (_: InterruptedException) {}
            }
            try { stream.use { copy(it, source, total = total) } } finally { watchdog.interrupt(); input = null }
            if (source.length() == 0L) fail("The selected file is empty.")
            ZipFile(source).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") != null) {
                    val parsed = info(source) ?: fail("Invalid APK, or a split APK selected without its base APK. Select the complete APK/APKS/APKM/XAPK file.")
                    return result(directory, name, parsed, listOf(source), emptyList())
                }
                val entries = mutableListOf<ZipEntry>()
                val names = hashSetOf<String>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    if (!names.add(entry.name)) fail("Archive contains duplicate paths.")
                    if (names.size > 4096) fail("Archive contains too many files.")
                    if (!entry.isDirectory) entries.add(entry)
                }
                var apks = entries.filter { it.name.endsWith(".apk", true) }
                if (apks.isEmpty()) fail("No APKs found. Select an APK or an unencrypted APKS, APKM, or XAPK archive.")
                if (apks.size > 512) fail("Archive contains too many APKs.")
                val toc = zip.getEntry("toc.pb")
                if (toc != null) {
                    val bytes = zip.getInputStream(toc).use { readSmall(it, 4 * 1024 * 1024) }
                    val selected = BundleApks(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
                        context.resources.displayMetrics.densityDpi, context.packageManager::hasSystemFeature).select(bytes)
                    if (!apks.map { it.name }.containsAll(selected)) fail("APKS index references missing APKs.")
                    apks = apks.filter { it.name in selected }
                }
                if (apks.isEmpty()) fail("This archive has no compatible APKs for this device.")
                val payload = File(directory, "apks").apply { check(mkdir()) }
                var files = apks.mapIndexed { index, entry ->
                    // Never use archive paths as extraction destinations.
                    File(payload, "part-$index.apk").also { extract(zip, entry, it) }
                }
                if (toc == null) {
                    val infos = files.map { file ->
                        file to (runCatching { SplitManifest.read(file) }.getOrElse { fail("Cannot read the manifest of ${apks[files.indexOf(file)].name}: ${it.message}") })
                    }
                    val selected = SplitManifest.select(infos, Build.SUPPORTED_ABIS.toList(), context.resources.displayMetrics.densityDpi)
                    files.filter { it !in selected }.forEach { if (!it.delete()) fail("Cannot remove an unused split from private cache.") }
                    files = selected
                }
                val parsed = info(if (files.size == 1) files.single() else payload)
                    ?: fail("Android could not parse this APK set. It must contain one base APK and matching splits for the same package/version. The archive may be incomplete or incompatible with this device.")
                if (!PmParser.validPackage(parsed.packageName)) fail("Archive has an invalid package name.")
                val obbNames = hashSetOf<String>()
                val expansions = entries.filter { it.name.endsWith(".obb", true) }.mapIndexed { index, entry ->
                    val filename = entry.name.substringAfterLast('/')
                    if (!Regex("(?:main|patch)\\.[0-9]+\\.${Regex.escape(parsed.packageName)}\\.obb").matches(filename)) {
                        fail("XAPK expansion filename does not match ${parsed.packageName}.")
                    }
                    if (!obbNames.add(filename)) fail("Duplicate XAPK expansion file: $filename")
                    Expansion(File(directory, "expansion-$index.obb").also { extract(zip, entry, it) }, filename)
                }
                if (!source.delete()) fail("Cannot remove the extracted archive from private cache.")
                return result(directory, name, parsed, files, expansions)
            }
        } catch (e: Exception) {
            directory.deleteRecursively()
            if (stalled) throw InstallerException(ErrorKind.ApkUnreadable, "The file source stopped responding.")
            if (aborted || e is CancellationException) throw CancellationException()
            if (e is InstallerException) throw e
            throw InstallerException(ErrorKind.ApkParseFailed,
                "Cannot read this APK/archive: ${e.message}. Encrypted archives are not supported.")
        }
    }

    @Suppress("DEPRECATION")
    private fun info(file: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(file.path, 0)

    private fun result(directory: File, name: String?, info: PackageInfo, files: List<File>, expansions: List<Expansion>): Apk {
        if (aborted) throw CancellationException()
        val app = info.applicationInfo ?: fail("APK has no application metadata.")
        if (!PmParser.validPackage(info.packageName)) fail("Invalid APK package name.")
        // Android's cluster parser validates split identities, version codes and dependencies.
        val base = files.find { it.path == app.sourceDir } ?: files.singleOrNull()
            ?: files.firstOrNull { this.info(it) != null } ?: fail("The archive is missing a base APK.")
        app.sourceDir = base.path
        app.publicSourceDir = base.path
        val splits = files.filter { it != base }
        app.splitSourceDirs = splits.map { it.path }.toTypedArray()
        app.splitPublicSourceDirs = app.splitSourceDirs
        val parts = (listOf(base) + splits).mapIndexed { index, file ->
            ApkPart(file, if (index == 0) "base.apk" else "split-$index.apk")
        }
        val label = runCatching { app.loadLabel(context.packageManager).toString() }.getOrDefault(info.packageName)
        val icon = runCatching {
            val drawable = app.loadIcon(context.packageManager)
            val pixels = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
            createBitmap(pixels, pixels).also {
                drawable.setBounds(0, 0, pixels, pixels)
                drawable.draw(Canvas(it))
            }
        }.getOrNull()
        val signers = runCatching { context.packageManager.getPackageArchiveInfo(base.path, SIGNING_FLAG)?.signers }.getOrNull().orEmpty()
        return Apk(directory, parts, expansions, name ?: "Selected file", info.packageName, label, info.versionName ?: "Unknown", icon, info.code, signers)
    }

    private fun extract(zip: ZipFile, entry: ZipEntry, file: File) {
        val crc = CRC32()
        val size = zip.getInputStream(entry).use { copy(it, file, crc) }
        if ((entry.size >= 0 && size != entry.size) || (entry.crc >= 0 && crc.value != entry.crc)) fail("Corrupt archive entry: ${entry.name}")
    }

    // These are live cache files: reclaiming cache via allocateBytes could evict the
    // source archive or earlier splits. Require real free space and retain a reserve.
    @SuppressLint("UsableSpace")
    private fun copy(input: InputStream, file: File, crc: CRC32? = null, total: Long? = null): Long {
        var written = 0L
        var reported = 0L
        val buffer = ByteArray(64 * 1024)
        file.outputStream().use { output ->
            while (true) {
                if (aborted) throw CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                lastRead = System.nanoTime()
                copiedBytes += count
                written += count
                if (crc == null && written - reported >= 4L * 1024 * 1024) { reported = written; progress(written, total) }
                if (copiedBytes > 16L * 1024 * 1024 * 1024) fail("Selected archive exceeds the 16 GiB processing limit.")
                if (context.cacheDir.usableSpace < count + 16L * 1024 * 1024) {
                    throw InstallerException(ErrorKind.ApkUnreadable, "Not enough free storage to unpack the selected file.")
                }
                output.write(buffer, 0, count)
                crc?.update(buffer, 0, count)
            }
        }
        return written
    }

    private fun readSmall(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) fail("APKS index is too large.")
            output.write(buffer, 0, count)
        }
    }

    private fun fail(message: String): Nothing = throw InstallerException(ErrorKind.ApkParseFailed, message)
}
