// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

/** Reads bundletool's small toc.pb index without shipping bundletool/protobuf runtimes. */
internal class BundleApks(private val sdkVersion: Int, private val supportedAbis: List<String>,
    private val density: Int, private val hasFeature: (String, Int) -> Boolean) {
    private val abiNames = listOf("", "armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64", "mips", "mips64", "riscv64")
    private val abis = supportedAbis.map { abiNames.indexOf(it) }.filter { it > 0 }

    fun select(bytes: ByteArray): Set<String> {
        val toc = Proto(bytes)
        val variants = toc.messages(1).sortedByDescending { it.number(3) }
        for (variant in variants) {
            if (!target(variant.message(1), true)) continue
            val paths = linkedSetOf<String>()
            var hasBase = false
            for (set in variant.messages(2)) {
                val module = set.message(1)
                if (module.number(3) != 0L) continue // Instant apps are a different installation mode.
                if (module.text(1) != "base" && (module.number(2) != 0L || module.number(6) == 2L)) continue
                if (!moduleMatches(module.message(5))) continue
                val selected = set.messages(2).filter { apk ->
                    (apk.has(3) || apk.has(4)) && target(apk.message(1), false)
                }
                for (apk in selected) {
                    val path = apk.text(2)
                    if (!path.endsWith(".apk", true)) fail("Invalid APK path in APKS index.")
                    paths.add(path)
                    if (module.text(1) == "base") hasBase = true
                }
            }
            if (!hasBase) continue
            // Install-time asset packs are APK slices. Other asset-delivery modes need Play.
            for (set in toc.messages(3)) {
                val module = set.message(1)
                if (module.number(4) != 1L) continue
                if (module.message(6).fields.isNotEmpty()) fail("This APKS uses unsupported conditional asset delivery. Use a device-specific APK set.")
                for (apk in set.messages(2)) {
                    if (apk.has(7) && target(apk.message(1), false)) paths.add(apk.text(2))
                }
            }
            return paths
        }
        fail("No compatible APK variant was found for Android $sdkVersion / ${supportedAbis.joinToString()}.")
    }

    private fun moduleMatches(t: Proto): Boolean {
        if (t.fields.any { it.id !in setOf(1, 2) }) fail("Unsupported conditional module targeting. Use a device-specific APKS export.")
        if (t.has(1) && !sdk(t.message(1))) return false
        return t.messages(2).all {
            val feature = it.message(1)
            hasFeature(feature.text(1), feature.number(2).toInt())
        }
    }

    private fun target(t: Proto, variant: Boolean): Boolean {
        val allowed = if (variant) setOf(1, 2, 3, 4, 6) else setOf(1, 3, 4, 5, 7)
        if (t.fields.any { it.id !in allowed }) fail("This APKS uses unsupported texture/device targeting. Use a device-specific APKS export.")
        if (variant && t.message(6).number(1) != 0L) return false
        val sdkField = if (variant) 1 else 5
        if (t.has(sdkField) && !sdk(t.message(sdkField))) return false
        val abiField = if (variant) 2 else 1
        if (t.has(abiField)) {
            val a = t.message(abiField)
            val values = a.messages(1).map { it.number(1).toInt() }
            val alternatives = a.messages(2).map { it.number(1).toInt() }
            val best = abis.firstOrNull { it in values || it in alternatives }
            if (if (values.isEmpty()) best != null else best !in values) return false
        }
        val multiField = if (variant) 4 else 7
        if (t.has(multiField)) {
            val multi = t.message(multiField)
            fun group(p: Proto) = p.messages(1).map { it.number(1).toInt() }.toSet()
            val values = multi.messages(1).map(::group)
            val alternatives = multi.messages(2).map(::group)
            val best = (values + alternatives).filter { it.isNotEmpty() && abis.containsAll(it) }
                .sortedWith(compareBy<Set<Int>> { set -> set.minOf { abis.indexOf(it) } }.thenByDescending { it.size }).firstOrNull()
            if (if (values.isEmpty()) best != null else best !in values) return false
        }
        val densityField = if (variant) 3 else 4
        if (t.has(densityField)) {
            val d = t.message(densityField)
            fun dpi(p: Proto): Int = if (p.has(2)) p.number(2).toInt() else
                mapOf(1 to 0, 2 to 120, 3 to 160, 4 to 213, 5 to 240, 6 to 320, 7 to 480, 8 to 640)[p.number(1).toInt()]
                    ?: fail("Unsupported density in APKS index.")
            val values = d.messages(1).map(::dpi)
            val alternatives = d.messages(2).map(::dpi)
            val best = bestDensity(values + alternatives, density)
            if (if (values.isEmpty()) best != null else best !in values) return false
        }
        // Include every language split in the chosen variant, allowing language changes offline.
        return true
    }

    private fun sdk(t: Proto): Boolean {
        fun min(p: Proto) = p.message(1).number(1).toInt()
        val values = t.messages(1).map(::min).ifEmpty { listOf(0) }
        val alternatives = t.messages(2).map(::min)
        val selected = (values + alternatives).filter { it <= sdkVersion }.maxOrNull()
        return selected != null && selected in values
    }

    companion object {
        fun bestDensity(values: List<Int>, target: Int): Int? = values.distinct().reduceOrNull { current, next ->
            val low = minOf(current, next).toLong()
            val high = maxOf(current, next).toLong()
            when {
                low == 0L -> 0 // nodpi applies independently of screen density.
                target >= high -> high.toInt()
                target <= low -> low.toInt()
                (2 * low - target) * high > target.toLong() * target -> low.toInt()
                else -> high.toInt()
            }
        }
        private fun fail(message: String): Nothing = throw InstallerException(ErrorKind.ApkParseFailed, message)
    }

    private data class Field(val id: Int, val number: Long = 0, val bytes: ByteArray? = null)
    private class Proto(data: ByteArray = byteArrayOf()) {
        val fields = mutableListOf<Field>()
        init {
            var pos = 0
            fun varint(): Long {
                var result = 0L
                for (shift in 0..63 step 7) {
                    if (pos >= data.size) fail("Truncated APKS index.")
                    val next = data[pos++].toInt() and 255
                    if (shift == 63 && next > 1) fail("Invalid APKS integer.")
                    result = result or ((next and 127).toLong() shl shift)
                    if (next and 128 == 0) return result
                }
                fail("Invalid APKS integer.")
            }
            while (pos < data.size) {
                if (fields.size > 100_000) fail("APKS index has too many entries.")
                val tag = varint()
                val id = (tag ushr 3).toInt()
                if (id <= 0) fail("Invalid APKS field.")
                when ((tag and 7).toInt()) {
                    0 -> fields.add(Field(id, varint()))
                    2 -> {
                        val size = varint()
                        if (size < 0 || size > data.size - pos) fail("Invalid APKS field size.")
                        fields.add(Field(id, bytes = data.copyOfRange(pos, pos + size.toInt())))
                        pos += size.toInt()
                    }
                    1, 5 -> {
                        val size = if (tag and 7 == 1L) 8 else 4
                        if (size > data.size - pos) fail("Truncated APKS index.")
                        pos += size
                        fields.add(Field(id))
                    }
                    else -> fail("Unsupported APKS index encoding.")
                }
            }
        }
        fun has(id: Int) = fields.any { it.id == id }
        fun number(id: Int) = fields.firstOrNull { it.id == id }?.number ?: 0L
        fun text(id: Int) = fields.firstOrNull { it.id == id }?.bytes?.toString(Charsets.UTF_8).orEmpty()
        fun messages(id: Int) = fields.filter { it.id == id }.map { Proto(it.bytes ?: fail("Invalid APKS message.")) }
        fun message(id: Int) = fields.firstOrNull { it.id == id }?.let { Proto(it.bytes ?: fail("Invalid APKS message.")) } ?: Proto()
    }
}
