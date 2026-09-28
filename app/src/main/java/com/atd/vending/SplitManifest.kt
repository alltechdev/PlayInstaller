// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

data class SplitInfo(val split: String?, val configForSplit: String?, val feature: Boolean) {
    val config: String? get() = split?.substringAfterLast("config.", "")?.takeIf { it.isNotEmpty() && split.contains("config.") }
    val parent: String get() = configForSplit.orEmpty()
}

/** Reads the manifest root attributes that identify a split; only what pm itself keys on. */
object SplitManifest {
    private val ABIS = mapOf("arm64_v8a" to "arm64-v8a", "armeabi_v7a" to "armeabi-v7a", "armeabi" to "armeabi",
        "x86_64" to "x86_64", "x86" to "x86", "riscv64" to "riscv64", "mips" to "mips", "mips64" to "mips64")
    private val DENSITIES = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
        "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640, "nodpi" to 0)

    fun read(apk: File): SplitInfo = ZipFile(apk).use { zip ->
        val entry = zip.getEntry("AndroidManifest.xml") ?: throw IllegalArgumentException("No manifest")
        if (entry.size > 4 * 1024 * 1024) throw IllegalArgumentException("Manifest too large")
        parse(zip.getInputStream(entry).use { it.readBytes() })
    }

    fun parse(bytes: ByteArray): SplitInfo {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 8 && buffer.getShort(0).toInt() == 0x0003) { "Not a binary XML manifest" }
        var strings: List<String>? = null
        var offset = buffer.getShort(2).toInt() and 0xFFFF
        while (offset + 8 <= bytes.size) {
            val type = buffer.getShort(offset).toInt() and 0xFFFF
            val headerSize = buffer.getShort(offset + 2).toInt() and 0xFFFF
            val size = buffer.getInt(offset + 4)
            require(size >= 8 && offset + size <= bytes.size) { "Corrupt manifest chunk" }
            when (type) {
                0x0001 -> strings = stringPool(buffer, offset)
                0x0102 -> {
                    val pool = strings ?: throw IllegalArgumentException("Manifest element before string pool")
                    var split: String? = null
                    var configFor: String? = null
                    var feature = false
                    val start = offset + headerSize
                    val attributeStart = buffer.getShort(start + 8).toInt() and 0xFFFF
                    val attributeSize = buffer.getShort(start + 10).toInt() and 0xFFFF
                    val count = buffer.getShort(start + 12).toInt() and 0xFFFF
                    for (index in 0 until count) {
                        val at = start + attributeStart + index * attributeSize
                        val name = pool.getOrNull(buffer.getInt(at + 4)) ?: continue
                        val raw = buffer.getInt(at + 8)
                        val dataType = buffer.get(at + 15).toInt() and 0xFF
                        val data = buffer.getInt(at + 16)
                        val text = when {
                            raw >= 0 -> pool.getOrNull(raw)
                            dataType == 0x03 -> pool.getOrNull(data)
                            else -> null
                        }
                        when (name) {
                            "split" -> split = text?.takeIf { it.isNotEmpty() }
                            "configForSplit" -> configFor = text?.takeIf { it.isNotEmpty() }
                            "isFeatureSplit" -> feature = if (dataType == 0x12) data != 0 else text == "true"
                        }
                    }
                    return SplitInfo(split, configFor, feature)
                }
            }
            offset += size
        }
        throw IllegalArgumentException("Manifest has no root element")
    }

    private fun stringPool(buffer: ByteBuffer, offset: Int): List<String> {
        val count = buffer.getInt(offset + 8)
        val utf8 = buffer.getInt(offset + 16) and (1 shl 8) != 0
        val stringsStart = offset + buffer.getInt(offset + 20)
        require(count in 0..65535) { "Corrupt string pool" }
        return List(count) { index ->
            var at = stringsStart + buffer.getInt(offset + 28 + index * 4)
            if (utf8) {
                if (buffer.get(at).toInt() and 0x80 != 0) at += 2 else at += 1
                var length = buffer.get(at).toInt() and 0xFF
                at += 1
                if (length and 0x80 != 0) { length = (length and 0x7F shl 8) or (buffer.get(at).toInt() and 0xFF); at += 1 }
                String(buffer.array(), at, length, Charsets.UTF_8)
            } else {
                var length = buffer.getShort(at).toInt() and 0xFFFF
                at += 2
                if (length and 0x8000 != 0) { length = (length and 0x7FFF shl 16) or (buffer.getShort(at).toInt() and 0xFFFF); at += 2 }
                String(buffer.array(), at, length * 2, Charsets.UTF_16LE)
            }
        }
    }

    fun <T> select(apks: List<Pair<T, SplitInfo>>, supportedAbis: List<String>, density: Int): List<T> {
        val bases = apks.filter { it.second.split == null }
        if (bases.isEmpty()) fail("The archive is missing a base APK.")
        if (bases.size > 1) fail("The archive contains ${bases.size} base APKs. Select an archive for one app.")
        val abisPresent = apks.mapNotNull { (_, info) -> ABIS[info.config] }.toSet()
        val abi = supportedAbis.firstOrNull { it in abisPresent }
        if (abisPresent.isNotEmpty() && abi == null) fail("This archive targets ${abisPresent.joinToString()}, but this device supports ${supportedAbis.joinToString()}.")
        val chosenDensity = apks.filter { DENSITIES.containsKey(it.second.config) }.groupBy { it.second.parent }
            .mapValues { (_, group) -> BundleApks.bestDensity(group.map { DENSITIES.getValue(it.second.config!!) }, density) }
        val kept = apks.filter { (_, info) ->
            val config = info.config
            when {
                config == null -> true
                ABIS.containsKey(config) -> ABIS[config] == abi
                DENSITIES.containsKey(config) -> DENSITIES[config] == chosenDensity[info.parent]
                else -> true
            }
        }
        val names = kept.map { it.second.split }.toSet()
        return kept.filter { (_, info) -> info.configForSplit == null || info.configForSplit in names }.map { it.first }
    }

    private fun fail(message: String): Nothing = throw InstallerException(ErrorKind.ApkParseFailed, message)
}
