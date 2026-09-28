// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry

class ArchiveSelectionTest {
    private fun flat(vararg names: String, density: Int = 420) =
        ApkParser.selectFlat(names.map(::ZipEntry), listOf("arm64-v8a", "armeabi-v7a"), density).map { it.name }

    @Test fun flatArchivesKeepBaseFeaturesLanguagesAndMatchingAbi() {
        assertEquals(listOf("base.apk", "feature.apk", "split_config.arm64_v8a.apk", "split_config.fr.apk"),
            flat("base.apk", "feature.apk", "split_config.arm64_v8a.apk", "split_config.x86.apk", "split_config.fr.apk"))
    }

    @Test fun densityIsChosenPerModuleAndCaseInsensitively() {
        assertEquals(listOf("BASE.APK", "CONFIG.XXHDPI.APK", "feature-config.hdpi.apk"),
            flat("BASE.APK", "CONFIG.HDPI.APK", "CONFIG.XXHDPI.APK", "feature-config.hdpi.apk"))
    }

    @Test fun universalAndIncompatibleAbiAreHandledExplicitly() {
        assertEquals(listOf("universal.apk"), flat("base.apk", "universal.apk", "config.x86.apk"))
        assertThrows(InstallerException::class.java) { flat("base.apk", "config.x86.apk") }
    }

    @Test fun densityMatchingPrefersDownscalingAtTheBoundary() {
        mapOf(100 to 160, 160 to 160, 200 to 240, 320 to 320, 420 to 480, 1000 to 480).forEach { (device, expected) ->
            assertEquals(expected, BundleApks.bestDensity(listOf(160, 240, 320, 480), device))
        }
        assertNull(BundleApks.bestDensity(emptyList(), 420))
    }

    private fun selector(sdk: Int = 35, features: (String, Int) -> Boolean = { _, _ -> false }) =
        BundleApks(sdk, listOf("arm64-v8a", "armeabi-v7a"), 420, features)

    // Tiny fixture encoder for bundletool's published wire schema, not a second selector.
    private fun varint(value: Int): ByteArray = ByteArrayOutputStream().apply {
        var remaining = value
        do {
            val next = remaining and 127
            remaining = remaining ushr 7
            write(next or if (remaining != 0) 128 else 0)
        } while (remaining != 0)
    }.toByteArray()
    private fun number(id: Int, value: Int) = varint(id shl 3) + varint(value)
    private fun field(id: Int, vararg values: ByteArray): ByteArray {
        val body = values.fold(byteArrayOf()) { result, bytes -> result + bytes }
        return varint((id shl 3) or 2) + varint(body.size) + body
    }
    private fun text(id: Int, value: String) = field(id, value.toByteArray())
    private fun apk(name: String, target: ByteArray = byteArrayOf()) = field(2, field(1, target), text(2, name), field(3))
    private fun module(name: String, vararg apks: ByteArray, metadata: ByteArray = byteArrayOf()) =
        field(2, field(1, text(1, name), metadata), *apks)
    private fun variant(number: Int, vararg modules: ByteArray, target: ByteArray = byteArrayOf()) =
        field(1, field(1, target), *modules, number(3, number))
    private fun sdk(minimum: Int) = field(1, field(1, field(1, number(1, minimum))))
    private fun abi(value: Int, alternative: Int) = field(1, field(1, number(1, value)), field(2, number(1, alternative)))

    @Test fun apksSelectsCompatibleVariantAbiAndAllLanguages() {
        val compatible = variant(1, module("base", apk("base.apk"),
            apk("arm64.apk", abi(3, 4)), apk("x86.apk", abi(4, 3)),
            apk("fr.apk", field(3, text(1, "fr"))), apk("en.apk", field(3, text(1, "en")))))
        val tooNew = variant(2, module("base", apk("new.apk")), target = sdk(36))
        assertEquals(setOf("base.apk", "arm64.apk", "fr.apk", "en.apk"), selector().select(compatible + tooNew))
    }

    @Test fun apksPrefersTheHighestCompatibleVariant() {
        val old = variant(1, module("base", apk("old.apk")), target = sdk(26))
        val current = variant(2, module("base", apk("current.apk")), target = sdk(30))
        assertEquals(setOf("current.apk"), selector().select(old + current))
        assertEquals(setOf("old.apk"), selector(28).select(old + current))
    }

    @Test fun apksDensityAlternativesSelectOnlyOneSplit() {
        fun density(value: Int, alternative: Int) = field(4,
            field(1, number(2, value)), field(2, number(2, alternative)))
        val toc = variant(1, module("base", apk("base.apk"),
            apk("hdpi.apk", density(240, 480)), apk("xxhdpi.apk", density(480, 240))))
        assertEquals(setOf("base.apk", "xxhdpi.apk"), selector().select(toc))
    }

    @Test fun conditionalAndOnDemandFeaturesAreNotBlindlyInstalled() {
        val camera = field(5, field(2, field(1, text(1, "android.hardware.camera"), number(2, 1))))
        val toc = variant(1, module("base", apk("base.apk")),
            module("camera", apk("camera.apk"), metadata = camera),
            module("later", apk("later.apk"), metadata = number(6, 2)))
        assertEquals(setOf("base.apk"), selector().select(toc))
        assertEquals(setOf("base.apk", "camera.apk"), selector(features = { name, version ->
            name == "android.hardware.camera" && version == 1
        }).select(toc))
    }

    @Test fun unsupportedTargetingAndMalformedIndexesFailClosed() {
        val unsupported = variant(1, module("base", apk("base.apk", field(11))))
        val cases = listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(10, 5, 1),
            byteArrayOf(8, 0x80.toByte()), byteArrayOf(15), unsupported)
        for (bytes in cases) {
            assertEquals(ErrorKind.ApkParseFailed,
                assertThrows(InstallerException::class.java) { selector().select(bytes) }.kind)
        }
    }
}
