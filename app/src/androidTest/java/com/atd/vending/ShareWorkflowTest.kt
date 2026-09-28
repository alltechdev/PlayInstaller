// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.app.Instrumentation.ActivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as InstallerApplication
    private val provider = Uri.parse("content://com.atd.vending.test.files")
    private fun control(method: String) = app.contentResolver.call(provider, method, null, null)!!
    private fun reads() = control("count").getInt("reads")
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) fail(message)
            SystemClock.sleep(25)
        }
    }
    private fun hasText(text: String): Boolean {
        fun contains(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.toString()?.contains(text) == true) return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        return contains(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun button(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) {
                var target: AccessibilityNodeInfo = node
                while (!target.isClickable) target = target.parent ?: return null
                return target
            }
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun share(path: String) = Intent(app, ShareInstallActivity::class.java).apply {
        action = Intent.ACTION_SEND
        type = "application/vnd.android.package-archive"
        val uri = Uri.withAppendedPath(provider, path)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("APK", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Before fun reset() {
        control("release")
        await("Previous operation did not finish") { !app.ui.value.busy }
        onMain { app.clearSelection() }
        await("Selection did not clear") { !app.ui.value.busy }
        control("reset")
    }

    @After fun cleanup() {
        control("release")
        await("File read did not finish") { !app.ui.value.busy }
        onMain { app.clearSelection() }
        await("Selection did not clear") { !app.ui.value.busy }
    }

    private fun shareMany(vararg paths: String) = Intent(app, ShareInstallActivity::class.java).apply {
        action = Intent.ACTION_SEND_MULTIPLE
        type = "application/vnd.android.package-archive"
        val uris = paths.map { Uri.withAppendedPath(provider, it) }
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = ClipData.newRawUri("APK", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun batchSharesAdvanceThroughSkipsAndFailuresToASummary() {
        ActivityScenario.launch<ShareInstallActivity>(shareMany("apk", "missing", "apk")).use {
            await("First item not ready") { !app.ui.value.busy && app.ui.value.canInstall && button("Skip") != null }
            assertTrue(hasText("(1 of 3)"))
            assertEquals(listOf(QueueStatus.Ready, QueueStatus.Pending, QueueStatus.Pending), app.ui.value.queue.map { it.status })
            assertEquals("PlayInstaller", app.ui.value.queue[0].name)
            assertTrue(button("Skip")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Third item not ready") { !app.ui.value.busy && app.ui.value.queueIndex == 2 && app.ui.value.canInstall }
            assertTrue(hasText("(3 of 3)"))
            assertEquals(listOf(QueueStatus.Skipped, QueueStatus.Failed, QueueStatus.Ready), app.ui.value.queue.map { it.status })
            assertTrue(app.ui.value.queue[1].detail.contains("Fixture unavailable"))
            await("Skip missing") { button("Skip") != null }
            assertTrue(button("Skip")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Summary missing") { !app.ui.value.busy && app.ui.value.selected == null && app.ui.value.result.isNotEmpty() }
            assertTrue(app.ui.value.result.startsWith("0 installed, 1 failed, 2 skipped"))
            assertTrue(app.ui.value.isError)
            await("Batch title missing") { hasText("Batch finished") && button("Close") != null }
            assertEquals(3, reads())
        }
    }

    @Suppress("DEPRECATION")
    @Test fun manifestAcceptsStreamOnlySharesAndContentViews() {
        val types = listOf("application/vnd.android.package-archive", "application/zip",
            "application/x-zip-compressed", "application/octet-stream", "application/vnd.apkm",
            "application/vnd.apks", "application/vnd.xapk")
        for (type in types) {
            for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE)) {
                val intent = Intent(action).setPackage(app.packageName).addCategory(Intent.CATEGORY_DEFAULT)
                if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
                    intent.type = type
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.withAppendedPath(provider, "apk"))
                } else intent.setDataAndType(Uri.withAppendedPath(provider, "apk"), type)
                val matches = app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                assertTrue("No handler for $action / $type", matches.any {
                    it.activityInfo.name == ShareInstallActivity::class.java.name
                })
            }
        }
    }

    @Test fun recreationWhileReadingReattachesWithoutReopening() {
        ActivityScenario.launch<ShareInstallActivity>(share("slow")).use { scenario ->
            await("Read did not start") { reads() == 1 && app.ui.value.busy }
            val selection = app.ui.value.selectionId
            scenario.recreate()
            await("Recreated dialog did not show progress") { hasText("Reading file") }
            assertFalse(hasText("Another operation"))
            assertEquals(selection, app.ui.value.selectionId)
            assertEquals(1, reads())
            control("release")
            await("APK did not load") { !app.ui.value.busy && app.ui.value.canInstall }
            await("Confirmation missing") { hasText("Install this app?") }
        }
    }

    @Test fun recreationAtConfirmationKeepsTheSamePrivateCopy() {
        ActivityScenario.launch<ShareInstallActivity>(share("apk")).use { scenario ->
            await("APK did not load") { !app.ui.value.busy && app.ui.value.canInstall }
            val selected = app.ui.value
            val cached = app.cacheDir.list()?.toSet()
            scenario.recreate()
            await("Confirmation missing after recreation") { hasText("Install this app?") }
            assertEquals(selected, app.ui.value)
            assertEquals(cached, app.cacheDir.list()?.toSet())
            assertEquals(1, reads())
        }
    }

    @Test fun recreationKeepsTheResultInsteadOfRetryingTheUri() {
        ActivityScenario.launch<ShareInstallActivity>(share("missing")).use { scenario ->
            await("Expected a read error") { reads() == 1 && !app.ui.value.busy && app.ui.value.isError }
            val result = app.ui.value.result
            scenario.recreate()
            await("Error result missing after recreation") { hasText("Fixture unavailable") }
            assertEquals(result, app.ui.value.result)
            assertEquals(1, reads())
        }
    }

    @Test fun recreationWithLostSelectionDoesNotRestartTheRequest() {
        ActivityScenario.launch<ShareInstallActivity>(share("apk")).use { scenario ->
            await("APK did not load") { !app.ui.value.busy && app.ui.value.canInstall }
            onMain { app.clearSelection() }
            await("Selection did not clear") { !app.ui.value.busy }
            scenario.recreate()
            await("Lost selection message missing") { hasText("no longer active") }
            assertEquals(1, reads())
            assertFalse(app.ui.value.canInstall)
        }
    }

    @Test fun rejectedShareCanCloseWithoutDisturbingAnActiveRead() {
        onMain { app.selectApk(Uri.withAppendedPath(provider, "slow")) }
        await("Read did not start") { reads() == 1 && app.ui.value.busy }
        val selection = app.ui.value.selectionId
        ActivityScenario.launch<ShareInstallActivity>(share("apk")).use { scenario ->
            await("Busy message missing") { hasText("Another operation") && hasText("Close") }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertEquals(selection, app.ui.value.selectionId)
            assertTrue(app.ui.value.busy)
            assertEquals(1, reads())
        }
    }

    @Test fun cancelDuringReadingClosesTheDialogWithoutAnError() {
        ActivityScenario.launch<ShareInstallActivity>(share("slow")).use { scenario ->
            await("Read did not start") { reads() == 1 && app.ui.value.reading && button("Cancel") != null }
            assertTrue(button("Cancel")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Read did not cancel") { !app.ui.value.busy }
            assertNull(app.ui.value.selected)
            assertEquals("", app.ui.value.result)
            assertFalse(app.ui.value.isError)
            await("Dialog did not close") { scenario.state == Lifecycle.State.DESTROYED }
            assertEquals(1, reads())
        }
    }

    @Test fun cancelButtonClearsTheSharedSelection() {
        ActivityScenario.launch<ShareInstallActivity>(share("apk")).use {
            await("Confirmation missing") { !app.ui.value.busy && button("Cancel")?.isEnabled == true }
            val installed = app.packageManager.getPackageInfo(app.packageName, SIGNING_FLAG)
            assertEquals(installedNote(installed.code, installed.versionName, installed.code, true), app.ui.value.selected!!.installed)
            assertTrue(hasText("already installed"))
            assertNotNull(button("Install"))
            assertNull(button("Uninstall"))
            assertTrue(button("Cancel")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Cancel did not clear the selection") { !app.ui.value.busy && app.ui.value.selected == null }
            assertNull(app.ui.value.selectionId)
            assertEquals(1, reads())
        }
    }

    @Test fun mainButtonsRemainDisabledDuringReadingAndEnableAfterward() {
        onMain { app.selectApk(Uri.withAppendedPath(provider, "slow")) }
        await("Read did not start") { reads() == 1 && app.ui.value.busy }
        ActivityScenario.launch(MainActivity::class.java).use {
            await("Disabled select button missing") { button("Select file")?.isEnabled == false }
            assertTrue(app.ui.value.busy)
            assertTrue(button("Cancel")!!.isEnabled)
            control("release")
            await("Install button did not enable") { !app.ui.value.busy && button("Install")?.isEnabled == true }
            assertTrue(app.ui.value.canInstall)
            assertEquals(1, reads())
        }
    }

    private fun serviceRunning(): Boolean {
        val manager = app.getSystemService(android.app.ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == InstallService::class.java.name && it.foreground }
    }

    @Test fun lastResultIsRestoredOnlyIntoAnIdleApp() {
        val saved = InstallerUiState(result = "Installation failed\n\napp.test\n\nboom", isError = true, log = "$ 'pm'\nFailure\n[exit 1]")
        assertTrue(LastResult.save(app.filesDir, saved).isSuccess)
        try {
            showState(InstallerUiState())
            ActivityScenario.launch(MainActivity::class.java).use {
                onMain { app.restoreLastResult() }
                await("Saved result missing") { app.ui.value == saved && hasText("boom") && button("Copy error") != null }
                assertNull(button("Open app"))
                showState(InstallerUiState(result = "newer", isError = true))
                onMain { app.restoreLastResult() }
                instrumentation.waitForIdleSync()
                assertEquals("newer", app.ui.value.result)
                onMain { app.clearSelection() }
                await("Clear did not finish") { !app.ui.value.busy }
                assertFalse(LastResult.file(app.filesDir).exists())
            }
        } finally { LastResult.clear(app.filesDir) }
    }

    @Test fun installedNotesResolveNonLaunchablePackages() {
        val shell = app.packageManager.getPackageInfo("com.android.shell", SIGNING_FLAG)
        assertNotNull(app.installedNote("com.android.shell", shell.code + 1, shell.signers))
        assertTrue(app.installedNote("com.android.shell", shell.code, setOf("0000"))!!.signatureMismatch)
        assertNull(app.installedNote("com.atd.absent", 1, emptySet()))
    }

    @Test fun installsRunInsideAForegroundService() {
        assertFalse(serviceRunning())
        InstallService.start(app, "Test")
        await("Foreground service did not start") { serviceRunning() }
        InstallService.stop(app)
        await("Foreground service did not stop") { !serviceRunning() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun showState(state: InstallerUiState) {
        val field = InstallerApplication::class.java.getDeclaredField("mutableUi").apply { isAccessible = true }
        onMain { (field.get(app) as MutableStateFlow<InstallerUiState>).value = state }
        instrumentation.waitForIdleSync()
    }

    @Test fun mainOpenAppIsAvailableOnlyForCompletedInstalls() {
        val selected = SelectedApp("Test", app.packageName, "1", "test.apk", "1 MB", null)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNull(button("Open app"))
            for (outcome in InstallOutcome.entries) {
                showState(InstallerUiState(selected = selected, result = outcome.title, outcome = outcome,
                    isError = outcome == InstallOutcome.Failure))
                await("Result missing") { hasText(outcome.title) }
                assertEquals(outcome.installed, button("Open app")?.isEnabled == true)
            }
            showState(InstallerUiState(selected = selected, result = "Installed", outcome = InstallOutcome.Success))
            scenario.recreate()
            await("Open app missing after recreation") { button("Open app")?.isEnabled == true }
            val monitor = ActivityMonitor(MainActivity::class.java.name, null, true)
            instrumentation.addMonitor(monitor)
            try {
                assertTrue(button("Open app")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Installed app was not launched") { monitor.hits == 1 }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            try {
                showState(app.ui.value.copy(busy = true, stage = "Installing…"))
                await("Open app remained visible while busy") { button("Open app") == null }
            } finally {
                showState(InstallerUiState())
            }
            await("Open app remained visible after clearing") { button("Open app") == null }
        }
    }

}
