// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture(parts: Int = 2, obb: Boolean = false) : PackageLookup {
        val directory = temporary.newFolder()
        val apk = Apk(directory, List(parts) { index ->
            ApkPart(File(directory, "part '$index.apk").apply { writeText("payload") }, if (index == 0) "base.apk" else "split-$index.apk")
        }, if (obb) listOf(Expansion(File(directory, "data.obb").apply { writeText("data") }, "main.1.app.test.obb")) else emptyList(),
            "test.apks", "app.test", "Test", "1")
        var identity = InstallerIdentity("com.android.vending", true, 12345, "Play Store")
        var metadata = PackageMetadata("com.android.vending", "com.android.vending")
        var rootDenied = false
        var reply: (String, List<String>) -> ShellResult? = { _, _ -> null }
        val calls = mutableListOf<List<String>>()
        val shells = mutableListOf<FakeShell>()
        override fun resolve(name: String) = identity
        override fun readPackageInfo(name: String) = metadata

        inner class FakeShell : CommandShell {
            var closed = false
            override fun execute(vararg args: String, timeoutMs: Long): ShellResult {
                check(!closed)
                val command = args.toList()
                calls.add(command)
                val op = operation(command)
                try {
                    return reply(op, command) ?: ShellResult(when (op) {
                        "id" -> identity.uid.toString()
                        "help" -> "install-create install-write install-commit --user"
                        "install-create" -> "Success: created session [42]"
                        else -> "Success"
                    }, "", 0)
                } catch (e: Exception) { close(); throw e }
            }
            override fun close() { closed = true }
        }

        fun controller() = InstallController({
            if (rootDenied) throw InstallerException(ErrorKind.RootUnavailable, "denied")
            FakeShell().also { shells.add(it) }
        }, { this }, {}, {})
        fun run(): InstallResult = controller().install(apk, identity.name)

        fun commands(op: String) = calls.filter { operation(it) == op }
        fun assertClean() {
            assertFalse(directory.exists())
            assertTrue(shells.all { it.closed })
            val staged = commands("cp").map { it.last() }.filter { it.startsWith("/data/local/tmp/") }
            val removed = commands("rm").map { it.last() }
            assertTrue(removed.containsAll(staged))
            assertTrue(commands("rm").all { it.size == 3 && it[1] == "-f" && '*' !in it.last() })
        }
    }

    private fun operation(args: List<String>): String = when (args.first()) {
        "su" -> listOf("install-create", "install-commit", "install-abandon", "id")
            .first { "'$it'" in args.last() }
        "pm" -> args[1]
        else -> args.first()
    }
    private fun failure() = ShellResult("", "INSTALL_FAILED_TEST", 1)

    @Test fun splitInstallUsesOneSessionAndDynamicCreatorAndCommitter() {
        for (uid in listOf(12345, 17001)) {
            val f = Fixture()
            f.identity = f.identity.copy(uid = uid)
            val result = f.run()
            assertTrue(result.error, result.success)
            assertEquals(42L, result.sessionId)
            assertEquals(1, f.commands("install-create").size)
            assertEquals(2, f.commands("install-write").size)
            assertEquals(setOf("base.apk", "split-1.apk"), f.commands("install-write").map { it[5] }.toSet())
            assertTrue(f.commands("install-write").all { it[4] == "42" })
            for (op in listOf("install-create", "install-commit")) {
                val command = f.commands(op).single()
                assertEquals(listOf("su", uid.toString(), "-c"), command.take(3))
                assertTrue(command.last().contains("actual_uid"))
            }
            assertTrue(f.commands("install-create").single().last().contains("'--user' '0'"))
            assertTrue(f.commands("install-abandon").isEmpty())
            f.assertClean()
        }
    }

    @Test fun installsNeverUninstallOrPassTheDowngradeFlag() {
        val f = Fixture()
        assertTrue(f.run().success)
        assertFalse("'-d'" in f.commands("install-create").single().last())
        assertTrue(f.commands("uninstall").isEmpty())
        f.assertClean()
    }

    @Test fun uninstallRunsExactlyOneRootCommandAndClosesTheShell() {
        val f = Fixture()
        f.controller().uninstall("app.test")
        assertEquals(listOf(listOf("pm", "uninstall", "--user", "0", "app.test")), f.calls.filter { it.first() == "pm" })
        assertTrue(f.shells.single().closed)
        f.reply = { op, _ -> if (op == "uninstall") failure() else null }
        try { f.controller().uninstall("app.test"); fail("Failure was ignored") } catch (e: InstallerException) { assertEquals(ErrorKind.CommandFailed, e.kind) }
        try { f.controller().uninstall("bad name"); fail("Invalid name accepted") } catch (_: IllegalArgumentException) {}
    }

    @Test fun everyFailedStageStopsAndCleansUp() {
        for (stage in listOf("cp", "chmod", "install-create", "install-write", "install-commit")) {
            val f = Fixture()
            f.reply = { op, _ -> if (op == stage) failure() else null }
            val result = f.run()
            assertFalse(stage, result.success)
            assertTrue(result.error!!.contains("INSTALL_FAILED_TEST"))
            assertEquals(stage, stage in listOf("install-write", "install-commit"), f.commands("install-abandon").isNotEmpty())
            if (stage != "install-commit") assertTrue(f.commands("install-commit").isEmpty())
            f.assertClean()
        }
    }

    @Test fun singleApkAndObbUseExactPathsAndCopyDataOnlyAfterCommit() {
        val f = Fixture(parts = 1, obb = true)
        assertTrue(f.run().success)
        assertEquals(1, f.commands("install-write").size)
        val copied = f.commands("cp").last()
        val moved = f.commands("mv").single()
        assertTrue(f.calls.indexOf(f.commands("install-commit").single()) < f.calls.indexOf(copied))
        assertEquals(copied.last(), moved[2])
        assertEquals("/storage/emulated/0/Android/obb/app.test/main.1.app.test.obb", moved.last())
        assertFalse(f.commands("rm").any { it.last() == moved.last() })
        f.assertClean()
    }

    @Test fun missingOrEmptyCachedApkNeverOpensRoot() {
        for (missing in listOf(true, false)) {
            val f = Fixture()
            if (missing) f.apk.parts.first().file.delete() else f.apk.parts.first().file.writeText("")
            assertFalse(f.run().success)
            assertTrue(f.shells.isEmpty())
            f.assertClean()
        }
    }

    @Test fun rootAndInstallerFailuresNeverCreateASession() {
        for (mode in listOf("root", "missing", "unknownUid", "rootUid", "identityMismatch")) {
            val f = Fixture()
            when (mode) {
                "root" -> f.rootDenied = true
                "missing" -> f.identity = f.identity.copy(installed = false)
                "unknownUid" -> f.identity = f.identity.copy(uid = null)
                "rootUid" -> f.identity = f.identity.copy(uid = 0)
                else -> f.reply = { op, _ -> if (op == "id") ShellResult("2000", "", 0) else null }
            }
            assertFalse(mode, f.run().success)
            assertTrue(f.commands("install-create").isEmpty())
            f.assertClean()
        }
    }

    @Test fun ambiguousSessionIdNeverWritesOrCommits() {
        val f = Fixture()
        f.reply = { op, _ -> if (op == "install-create") ShellResult("[42] [43]", "", 0) else null }
        assertTrue(f.run().error!!.contains("unambiguous session ID"))
        assertTrue(f.commands("install-write").isEmpty())
        assertTrue(f.commands("install-commit").isEmpty())
        f.assertClean()
    }

    @Test fun timeoutRetriesCleanupWithAFreshShell() {
        val f = Fixture()
        f.reply = { op, _ ->
            if (op == "install-write") throw InstallerException(ErrorKind.CommandFailed, "timed out")
            null
        }
        assertFalse(f.run().success)
        assertEquals(2, f.shells.size)
        assertEquals(1, f.commands("install-abandon").size)
        f.assertClean()
    }

    @Test fun abandonFallsBackToRoot() {
        val f = Fixture()
        f.reply = { op, args -> if (op == "install-write" || op == "install-abandon" && args.first() == "su") failure() else null }
        val result = f.run()
        assertFalse(result.success)
        assertEquals(listOf("su", "pm"), f.commands("install-abandon").map { it.first() })
        assertTrue(result.warnings.isEmpty())
        f.assertClean()
    }

    @Test fun cleanupRetryDoesNotAbandonAnAlreadyAbandonedSession() {
        val f = Fixture()
        var removed = false
        f.reply = { op, _ -> when {
            op == "install-write" -> failure()
            op == "rm" && !removed -> { removed = true; failure() }
            else -> null
        } }
        val result = f.run()
        assertEquals(2, f.shells.size)
        assertEquals(1, f.commands("install-abandon").size)
        assertTrue(result.warnings.isEmpty())
        f.assertClean()
    }

    @Test fun persistentCleanupFailureIsReportedEvenAfterCommit() {
        val f = Fixture()
        f.reply = { op, _ -> if (op == "rm") failure() else null }
        val result = f.run()
        assertTrue(result.success)
        assertTrue(result.warnings.single().contains("CleanupFailed"))
        assertTrue(f.commands("install-abandon").isEmpty())
        assertEquals(2, f.shells.size)
        f.assertClean()
    }

    @Test fun recordedMetadataIsNotReplacedWithRequestedMetadata() {
        val f = Fixture()
        f.metadata = PackageMetadata("com.android.vending", "com.android.shell")
        val result = f.run()
        assertTrue(result.success)
        assertEquals("com.android.shell", result.metadata!!.initiating)
        assertEquals(InstallOutcome.AttributionUnconfirmed, result.outcome)
        assertTrue(result.summary().startsWith("Installed, Play attribution unconfirmed"))
        assertTrue(result.warnings.single().contains("Attribution not confirmed"))
        f.assertClean()
    }

    @Test fun obbFailureDoesNotAbandonCommittedApksOrDeleteFinalData() {
        val f = Fixture(obb = true)
        f.reply = { op, _ -> if (op == "mv") failure() else null }
        val result = f.run()
        assertFalse(result.success)
        assertTrue(result.error!!.contains("APKs were installed"))
        assertTrue(f.commands("install-abandon").isEmpty())
        assertTrue(f.commands("rm").any { it.last().endsWith(".tmp") })
        assertFalse(f.commands("rm").any { it.last().endsWith(".obb") })
        f.assertClean()
    }

    @Test fun staleCopiesAreRemovedByExactPathBeforeStaging() {
        val f = Fixture()
        val apk = "/data/local/tmp/root-installer-0f8fad5b-d9cb-469f-a165-70867728950e-1.apk"
        val obb = "/storage/emulated/0/Android/obb/app.test/.root-installer-0f8fad5b-d9cb-469f-a165-70867728950e-0.tmp"
        val decoys = listOf("/data/local/tmp/root-installer-other.apk", "/data/local/tmp/root-installer-0f8fad5b-d9cb-469f-a165-70867728950e-1.apk\n/data/system",
            "/data/local/tmp/Root-Installer-0f8fad5b-d9cb-469f-a165-70867728950e-1.apk", "/storage/emulated/0/Android/obb/../.root-installer-0f8fad5b-d9cb-469f-a165-70867728950e-0.tmp",
            "/storage/emulated/0/Android/obb/app.test/.root-installer-0f8fad5b-d9cb-469f-a165-70867728950e-0.tmp.bak", "  ", "Success")
        f.reply = { op, args -> if (op == "find") ShellResult((decoys + if ("obb" in args[1]) obb else apk).joinToString("\n"), "", 0) else null }
        assertTrue(f.run().success)
        assertEquals(2, f.commands("find").size)
        assertTrue(f.commands("find").none { '*' in it[1] })
        val swept = f.calls.take(f.calls.indexOf(f.commands("cp").first())).filter { operation(it) == "rm" }.map { it.last() }
        assertEquals(listOf(apk, obb), swept)
        f.assertClean()
    }

    @Test fun staleFileListingFailuresDoNotBlockInstallation() {
        for (mode in listOf("find", "rm")) {
            val f = Fixture()
            f.reply = { op, args -> when {
                op == "find" && mode == "find" -> failure()
                op == "find" -> ShellResult("/data/local/tmp/root-installer-0f8fad5b-d9cb-469f-a165-70867728950e-0.apk", "", 0)
                op == "rm" && args.last().endsWith("950e-0.apk") -> failure()
                else -> null
            } }
            val result = f.run()
            assertTrue(mode, result.success)
            assertTrue(mode, result.warnings.isEmpty())
            f.assertClean()
        }
    }

    @Test fun clearingCachedSelectionDeletesOnlyItsOwnDirectory() {
        val f = Fixture(obb = true)
        val unrelated = temporary.newFile("keep.apk").apply { writeText("keep") }
        assertTrue(f.apk.delete())
        assertFalse(f.directory.exists())
        assertEquals("keep", unrelated.readText())
        assertTrue(f.apk.delete())
    }
}
