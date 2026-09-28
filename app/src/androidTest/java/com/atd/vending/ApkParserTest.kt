// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkParserTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InstallerApplication
    private val ownApk get() = File(app.applicationInfo.sourceDir).readBytes()
    private val fixtures = File(app.cacheDir, "fixtures")
    private var cached = emptySet<String>()

    @Before fun snapshot() {
        fixtures.deleteRecursively()
        fixtures.mkdirs()
        cached = app.cacheDir.list()!!.toSet()
    }

    @After fun cleanup() {
        val leaked = app.cacheDir.list()!!.toSet() - cached
        fixtures.deleteRecursively()
        assertEquals("Private copies leaked", emptySet<String>(), leaked)
    }

    private fun file(name: String, bytes: ByteArray) = File(fixtures, name).apply { writeBytes(bytes) }
    private fun zip(name: String, vararg entries: Pair<String, ByteArray>, stored: Boolean = false): File {
        val file = File(fixtures, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, bytes) in entries) {
                val entry = ZipEntry(path)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
    private fun read(file: File) = ApkParser(app).read(Uri.fromFile(file))
    private fun failure(file: File): InstallerException {
        val apk = try { read(file) } catch (e: InstallerException) { return e }
        apk.delete()
        throw AssertionError("Parsed ${file.name}")
    }

    @Test fun plainApkKeepsItsCopyAsTheOnlyPart() {
        val apk = read(file("plain.apk", ownApk))
        try {
            assertEquals(app.packageName, apk.packageName)
            assertEquals("PlayInstaller", apk.label)
            assertEquals("Selected file", apk.displayName)
            assertEquals(listOf("base.apk"), apk.parts.map { it.sessionName })
            assertEquals("source.apk", apk.parts.single().file.name)
            assertEquals(ownApk.size.toLong(), apk.size)
            assertTrue(apk.expansions.isEmpty())
            assertNotNull(apk.icon)
            assertEquals(app.packageManager.getPackageInfo(app.packageName, SIGNING_FLAG).signers, apk.signers)
            assertTrue(apk.signers.isNotEmpty())
            assertTrue(apk.directory.name.startsWith("selected-"))
            assertEquals(app.cacheDir, apk.directory.parentFile)
        } finally { assertTrue(apk.delete()) }
    }

    @Test fun contentUriSuppliesTheDisplayName() {
        val apk = ApkParser(app).read(Uri.parse("content://com.atd.vending.test.files/plain"))
        try { assertEquals("fixture.apk", apk.displayName) } finally { assertTrue(apk.delete()) }
    }

    @Test fun archivesAreExtractedUnderFixedNamesAndTheSourceIsDropped() {
        val apk = read(zip("bundle.xapk", "sub/Universal.APK" to ownApk, "readme.txt" to "hi".toByteArray()))
        try {
            assertEquals(app.packageName, apk.packageName)
            assertEquals(listOf("part-0.apk"), apk.parts.map { it.file.name })
            assertEquals(listOf("base.apk"), apk.parts.map { it.sessionName })
            assertEquals(setOf("apks"), apk.directory.list()!!.toSet())
            assertArrayEquals(ownApk, apk.parts.single().file.readBytes())
        } finally { assertTrue(apk.delete()) }
    }

    @Test fun xapkExpansionsAreExtractedWhenNamedForThePackage() {
        val data = ByteArray(3000) { it.toByte() }
        val apk = read(zip("game.xapk", "base.apk" to ownApk, "Android/obb/${app.packageName}/main.3.${app.packageName}.obb" to data))
        try {
            val expansion = apk.expansions.single()
            assertEquals("main.3.${app.packageName}.obb", expansion.name)
            assertEquals("expansion-0.obb", expansion.file.name)
            assertArrayEquals(data, expansion.file.readBytes())
            assertEquals(ownApk.size.toLong(), apk.size)
        } finally { assertTrue(apk.delete()) }
    }

    @Test fun expansionNamesMustMatchThePackageExactly() {
        for (name in listOf("main.1.com.other.obb", "main.1.${app.packageName}x.obb", "extra.1.${app.packageName}.obb",
            "main.${app.packageName}.obb", "main.1.${app.packageName}.OBB")) {
            val error = failure(zip("bad.xapk", "base.apk" to ownApk, name to ByteArray(8)))
            assertEquals(name, ErrorKind.ApkParseFailed, error.kind)
            assertTrue(name, error.message!!.contains("does not match"))
        }
        val duplicate = failure(zip("dup.xapk", "base.apk" to ownApk,
            "a/main.1.${app.packageName}.obb" to ByteArray(8), "b/main.1.${app.packageName}.obb" to ByteArray(8)))
        assertTrue(duplicate.message!!.contains("Duplicate XAPK expansion"))
    }

    @Test fun unusableInputsFailClosedWithSpecificReasons() {
        assertEquals("The selected file is empty.", failure(file("empty.apk", ByteArray(0))).message)
        val garbage = failure(file("garbage.apk", ByteArray(4096) { (it * 7).toByte() }))
        assertEquals(ErrorKind.ApkParseFailed, garbage.kind)
        assertTrue(garbage.message!!.startsWith("Cannot read this APK/archive"))
        assertTrue(failure(zip("none.zip", "readme.txt" to "hi".toByteArray())).message!!.startsWith("No APKs found"))
        assertTrue(failure(zip("fake.zip", "base.apk" to "not an apk".toByteArray())).message!!.startsWith("Cannot read the manifest of base.apk"))
        assertTrue(failure(file("fake.apk", zipBytes("AndroidManifest.xml" to "x".toByteArray()))).message!!.startsWith("Invalid APK"))
        assertTrue(failure(zip("empty-toc.apks", "base.apk" to ownApk, "toc.pb" to ByteArray(0))).message!!.startsWith("No compatible APK variant"))
        val missing = failure(File("/nonexistent/missing.apk"))
        assertEquals(ErrorKind.ApkParseFailed, missing.kind)
    }

    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("splits/$name.apk").use { it.readBytes() }

    @Test fun splitsAreSelectedByManifestNotFilename() {
        val archive = zip("renamed.apkm", "split_config.x86.apk" to fixture("base"), "b.apk" to fixture("arm64"),
            "split_config.arm64_v8a.apk" to fixture("x86"), "config.hdpi.apk" to fixture("xxhdpi"), "config.xxhdpi.apk" to fixture("hdpi"),
            "fr.apk" to fixture("fr"), "feature.apk" to fixture("assets"), "z.apk" to fixture("assetsxxhdpi"), "y.apk" to fixture("assetshdpi"))
        val apk = read(archive)
        try {
            assertEquals("com.atd.fixture", apk.packageName)
            assertEquals(7L, apk.versionCode)
            val splits = apk.parts.map { SplitManifest.read(it.file).split }
            assertEquals(null, splits.first())
            val density = if (BundleApks.bestDensity(listOf(240, 480), app.resources.displayMetrics.densityDpi) == 480) "xxhdpi" else "hdpi"
            assertEquals(setOf("config.arm64_v8a", "config.$density", "config.fr", "assets", "assets.config.$density"), splits.drop(1).toSet())
            assertEquals(apk.parts.map { it.file.name }.toSet(), apk.parts.first().file.parentFile!!.list()!!.toSet())
            assertEquals(1, apk.signers.size)
        } finally { assertTrue(apk.delete()) }
    }

    @Test fun archivesForOtherAbisOrWithoutABaseAreRejected() {
        assertTrue(failure(zip("x86.apkm", "base.apk" to fixture("base"), "config.x86.apk" to fixture("x86"))).message!!.contains("targets x86"))
        assertTrue(failure(zip("nobase.apkm", "a.apk" to fixture("arm64"), "b.apk" to fixture("fr"))).message!!.contains("missing a base APK"))
        assertTrue(failure(zip("twobase.apkm", "a.apk" to fixture("base"), "b.apk" to ownApk)).message!!.contains("2 base APKs"))
    }

    @Test fun corruptEntriesAreDetectedByChecksum() {
        val payload = ByteArray(8192) { (it * 31 + 7).toByte() }
        val archive = zip("corrupt.apks", "universal.apk" to payload, stored = true)
        val bytes = archive.readBytes()
        val offset = bytes.indexOf(payload.copyOf(64))
        assertTrue(offset > 0)
        bytes[offset + 100] = (bytes[offset + 100] + 1).toByte()
        archive.writeBytes(bytes)
        assertTrue(failure(archive).message!!.startsWith("Corrupt archive entry: universal.apk"))
    }

    @Test fun progressIsReportedEveryFourMebibytesAndAbortCancelsCleanly() {
        val mib = 1024L * 1024
        val big = file("big.apk", ByteArray(20 * mib.toInt() + 5) { (it * 13).toByte() })
        val reports = mutableListOf<Pair<Long, Long?>>()
        val error = try { ApkParser(app) { copied, total -> reports.add(copied to total) }.read(Uri.fromFile(big)); null }
            catch (e: InstallerException) { e }
        assertEquals(ErrorKind.ApkParseFailed, error!!.kind)
        assertEquals(listOf(4 * mib, 8 * mib, 12 * mib, 16 * mib, 20 * mib).map { it to null }, reports)
        lateinit var parser: ApkParser
        parser = ApkParser(app) { copied, _ -> if (copied >= 8 * mib) parser.abort() }
        try { parser.read(Uri.fromFile(big)); fail("Abort was ignored") } catch (_: CancellationException) {}
    }

    @Test fun stalledSourcesTimeOutWithoutLeavingACopy() {
        val provider = Uri.parse("content://com.atd.vending.test.files")
        app.contentResolver.call(provider, "reset", null, null)
        try {
            val started = System.nanoTime()
            val error = try { ApkParser(app, stallTimeoutMs = 2_000).read(Uri.withAppendedPath(provider, "slow")); null }
                catch (e: InstallerException) { e }
            assertEquals(ErrorKind.ApkUnreadable, error!!.kind)
            assertEquals("The file source stopped responding.", error.message)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
        } finally { app.contentResolver.call(provider, "release", null, null) }
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>) = zip("inner.zip", *entries).readBytes()
    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { start -> needle.indices.all { this[start + it] == needle[it] } } ?: -1
}
