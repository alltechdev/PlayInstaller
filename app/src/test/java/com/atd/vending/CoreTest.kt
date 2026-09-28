// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun installedNotesCompareVersionCodesAndFlagOnlyDowngrades() {
        assertEquals(InstalledNote("Update from version 1.2."), installedNote(12, "1.2", 13, true))
        assertEquals(InstalledNote("Version 1.3 is already installed and will be reinstalled."), installedNote(13, "1.3", 13, null))
        val downgrade = installedNote(14, "1.4", 13, true)
        assertTrue(downgrade.downgrade && !downgrade.signatureMismatch)
        assertTrue(downgrade.text.startsWith("Installed version 1.4 is newer."))
        assertTrue(downgrade.blocks)
        assertFalse(installedNote(12, "1.2", 13, true).blocks)
        assertTrue(installedNote(14, null, 13, true).text.startsWith("Installed version 14 is newer."))
        val mismatch = installedNote(12, "1.2", 13, false)
        assertTrue(mismatch.signatureMismatch && !mismatch.downgrade)
        assertTrue(mismatch.text.contains("different key"))
        assertTrue(mismatch.blocks)
    }

    @Test fun sessionIdsRequireOneUnambiguousPositiveInt() {
        mapOf("Success: created install session [42]" to 42L, "Créée [73]" to 73L,
            "\n91\n" to 91L, "[0]" to null, "[-1]" to null, "[2147483648]" to null,
            "[12] [13]" to null, "12\n13" to null, "version 35" to null, "" to null
        ).forEach { (output, expected) -> assertEquals(output, expected, PmParser.sessionId(output)) }
    }

    @Test fun successRequiresExitStatusAndNoAndroidError() {
        assertTrue(PmParser.succeeded(ShellResult("Success", "", 0)))
        listOf(ShellResult("Success", "", 1), ShellResult("Failure [bad APK]", "", 0),
            ShellResult("", "SecurityException: denied", 0), ShellResult("INSTALL_FAILED_INVALID_APK", "", 0)
        ).forEach { assertFalse(it.toString(), PmParser.succeeded(it)) }
    }

    @Test fun metadataAndUidAreReadExactlyWithoutInventingAttribution() {
        assertEquals(12345, PmParser.uid("package:com.android.vending uid:12345", "com.android.vending"))
        assertNull(PmParser.uid("package:com.android.vending.extra uid:12345", "com.android.vending"))
        assertNull(PmParser.uid("package:com.android.vending uid:12\npackage:com.android.vending uid:13", "com.android.vending"))
        val dump = "installerPackageName=com.android.vending initiatingPackageName=com.android.shell"
        assertEquals("com.android.vending", PmParser.field(dump, "installerPackageName"))
        assertEquals("com.android.shell", PmParser.field(dump, "initiatingPackageName"))
        assertNull(PmParser.field("initiatingPackageName=null", "initiatingPackageName"))
    }

    @Test fun sharedFilesRequireContentUris() {
        requireSharedFile("content", "files.example")
        listOf(null, "file", "https", "javascript").forEach { scheme ->
            assertThrows(IllegalArgumentException::class.java) { requireSharedFile(scheme, "files.example") }
        }
        listOf(null, "").forEach { authority ->
            assertThrows(IllegalArgumentException::class.java) { requireSharedFile("content", authority) }
        }
    }

    @Test fun batchSummaryCountsEveryOutcomeAndListsEachFile() {
        val queue = listOf(QueuedFile("content://a", "Maps", QueueStatus.Installed, "Installed successfully"),
            QueuedFile("content://b", "File 2", QueueStatus.Failed, "ApkParseFailed: bad"),
            QueuedFile("content://c", "Notes", QueueStatus.Skipped))
        assertEquals("1 installed, 1 failed, 1 skipped\n\nMaps: Installed · Installed successfully\nFile 2: Failed · ApkParseFailed: bad\nNotes: Skipped", queue.summary())
        assertTrue(InstallerUiState(queue = queue, queueIndex = 1).batch)
        assertEquals("File 2", InstallerUiState(queue = queue, queueIndex = 1).current!!.name)
        assertFalse(InstallerUiState(queue = queue.take(1)).batch)
        assertNull(InstallerUiState(queue = queue).current)
    }

    @Test fun packageNamesCannotBecomeShellSyntax() {
        listOf("app.vela", "com.android.vending", "android").forEach { assertTrue(PmParser.validPackage(it)) }
        listOf("", "a;id", "a/b", "a..b", "a b", "$(id)", "a\nb").forEach { assertFalse(PmParser.validPackage(it)) }
    }
}
