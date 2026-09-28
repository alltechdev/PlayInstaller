// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import org.junit.Assert.*
import org.junit.Test

class InstallOutcomeTest {
    private fun installed(initiating: String? = PLAY_STORE_PACKAGE, installer: String? = PLAY_STORE_PACKAGE,
        warnings: List<String> = emptyList()) = InstallResult(true, "app.test", 42,
        PackageMetadata(installer, initiating), null, warnings)

    @Test fun reportAppendsTheCommandLogOnlyWhenPresent() {
        val failed = InstallerUiState(result = "Installation failed", isError = true)
        assertEquals("Installation failed", failed.report())
        val report = failed.copy(log = "$ 'pm' 'install-create'\nFailure\n[exit 1]").report()
        assertTrue(report.startsWith("Installation failed\n\nCommand log:\n$ 'pm'"))
        assertTrue(report.endsWith("[exit 1]"))
    }

    @Test fun onlyExactPlayAttributionGetsUnqualifiedSuccess() {
        val result = installed()
        assertEquals(InstallOutcome.Success, result.outcome)
        assertTrue(result.summary().startsWith("Installed successfully"))
        assertTrue(result.outcome.installed)
        assertFalse(result.outcome.warning)
    }

    @Test fun unknownOrDifferentInitiatorsRemainInstalledButAreClearlyQualified() {
        for (initiating in listOf(null, "", "com.android.shell", "com.android.Vending",
            "com.android.vending.extra", " com.android.vending", "com.android.vending\n")) {
            val result = installed(initiating = initiating)
            assertEquals(initiating, InstallOutcome.AttributionUnconfirmed, result.outcome)
            assertTrue(result.success) // The APK was committed; this is not a rollback or install failure.
            assertTrue(result.outcome.installed)
            assertTrue(result.outcome.warning)
            assertTrue(result.summary().startsWith("Installed, Play attribution unconfirmed"))
            assertFalse(result.summary().contains("Installed successfully"))
            assertTrue(result.summary().contains("Initiating package: ${initiating ?: "Unknown"}"))
        }
    }

    @Test fun unavailableMetadataCannotBecomeSuccess() {
        val result = installed().copy(metadata = null, warnings = listOf("Metadata query failed"))
        assertEquals(InstallOutcome.AttributionUnconfirmed, result.outcome)
        assertTrue(result.summary().contains("Initiating package: Unknown"))
        assertTrue(result.summary().contains("Metadata query failed"))
    }

    @Test fun installerMismatchAlsoQualifiesTheResult() {
        for (installer in listOf(null, "com.android.shell")) {
            assertEquals(InstallOutcome.AttributionUnconfirmed, installed(installer = installer).outcome)
        }
    }

    @Test fun cleanupWarningsAreNotUnqualifiedSuccess() {
        val result = installed(warnings = listOf("CleanupFailed: temporary file remains"))
        assertEquals(InstallOutcome.WithWarnings, result.outcome)
        assertTrue(result.summary().startsWith("Installed with warnings"))
        assertTrue(result.outcome.installed)
        assertTrue(result.outcome.warning)
    }

    @Test fun installationFailureTakesPrecedenceOverAttribution() {
        val result = installed().copy(success = false, error = "Commit rejected")
        assertEquals(InstallOutcome.Failure, result.outcome)
        assertTrue(result.summary().startsWith("Installation failed"))
        assertTrue(result.summary().contains("Commit rejected"))
        assertFalse(result.outcome.installed)
    }
}
