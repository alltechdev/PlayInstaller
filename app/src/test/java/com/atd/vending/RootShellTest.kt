// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class RootShellTest {
    // Plain local sh only: fake id, never su or Android package commands.
    private fun shell(uid: String = "0") = RootShell {
        ProcessBuilder("sh").start().apply {
            outputStream.write("id() { printf '%s\\n' '$uid'; }\n".toByteArray())
            outputStream.flush()
        }
    }

    @Test(timeout = 5000) fun argumentsRoundTripThroughARealShell() {
        val values = listOf("", "My App.apk", "it's.apk", "$(printf injected)", "a; echo injected",
            "&|><`pwd`", "line\nbreak", "Unicode-地图.apk", "'\"\\")
        for (value in values) {
            val process = ProcessBuilder("sh", "-c", "printf '%s' ${ShellEscaper.quote(value)}").start()
            try {
                assertEquals(value, process.inputStream.bufferedReader().readText())
                assertTrue(process.waitFor(1, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            } finally { process.destroyForcibly() }
        }
        assertThrows(IllegalArgumentException::class.java) { ShellEscaper.quote("bad\u0000argument") }
    }

    @Test(timeout = 5000) fun capturesBothStreamsStatusAndConsecutiveCommands() {
        shell().open().use {
            val result = it.execute("sh", "-c", "printf out; printf err >&2; exit 7")
            assertEquals("out", result.stdout.trim())
            assertEquals("err", result.stderr.trim())
            assertEquals(7, result.exitCode)
            assertEquals("next", it.execute("printf", "%s", "next").stdout.trim())
        }
    }

    @Test(timeout = 5000) fun denialAndMissingSuAreStructuredErrors() {
        val denied = assertThrows(InstallerException::class.java) { shell("2000").open() }
        assertEquals(ErrorKind.RootUnavailable, denied.kind)
        val missing = assertThrows(InstallerException::class.java) { RootShell { throw IOException("missing") }.open() }
        assertEquals(ErrorKind.RootUnavailable, missing.kind)
    }

    @Test(timeout = 5000) fun timeoutClosesTheShellAndPreventsReuse() {
        shell().open().use {
            val error = assertThrows(InstallerException::class.java) {
                it.execute("sh", "-c", "sleep 0.2", timeoutMs = 10)
            }
            assertTrue(error.message!!.contains("timed out"))
            assertThrows(IllegalStateException::class.java) { it.execute("printf", "again") }
        }
    }

    @Test(timeout = 5000) fun outputLimitDoesNotDeadlockStatusFrames() {
        shell().open().use {
            val result = it.execute("sh", "-c", "head -c 600000 /dev/zero | tr '\\000' x")
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.length in 524288..540000)
            assertEquals("ok", it.execute("printf", "ok").stdout.trim())
        }
    }
}
