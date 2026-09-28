// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun installedNotesCompareVersionCodesAndFlagOnlyDowngrades() {
        assertEquals(InstalledNote("Update from version 1.2.", false), installedNote(12, "1.2", 13))
        assertEquals(InstalledNote("Version 1.3 is already installed and will be reinstalled.", false), installedNote(13, "1.3", 13))
        assertEquals(InstalledNote("Installed version 1.4 is newer. Installation will fail.", true), installedNote(14, "1.4", 13))
        assertEquals("Installed version 14 is newer. Installation will fail.", installedNote(14, null, 13).text)
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

    @Test fun sharedFilesRequireOneContentUri() {
        for (count in 0..1) requireSharedFile("content", "files.example", count)
        listOf(null, "file", "https", "javascript").forEach { scheme ->
            assertThrows(IllegalArgumentException::class.java) { requireSharedFile(scheme, "files.example", 1) }
        }
        listOf(null, "").forEach { authority ->
            assertThrows(IllegalArgumentException::class.java) { requireSharedFile("content", authority, 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { requireSharedFile("content", "files.example", 2) }
    }

    @Test fun packageNamesCannotBecomeShellSyntax() {
        listOf("app.vela", "com.android.vending", "android").forEach { assertTrue(PmParser.validPackage(it)) }
        listOf("", "a;id", "a/b", "a..b", "a b", "$(id)", "a\nb").forEach { assertFalse(PmParser.validPackage(it)) }
    }
}
