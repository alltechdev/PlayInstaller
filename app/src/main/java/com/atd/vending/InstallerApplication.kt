// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SelectedApp(val name: String, val packageName: String, val version: String,
    val filename: String, val details: String, val icon: Bitmap?, val installed: InstalledNote? = null)

data class InstallerUiState(
    val selectionId: String? = null,
    val selected: SelectedApp? = null,
    val canInstall: Boolean = false,
    val busy: Boolean = false,
    val stage: String = "",
    val result: String = "",
    val isError: Boolean = false,
    val outcome: InstallOutcome? = null,
    val log: String = ""
)

private const val LOG_LIMIT = 256 * 1024
private const val LOG_TRUNCATED = "[log truncated]\n"
private const val READING = "Reading file…"

val InstallerUiState.reading: Boolean get() = busy && stage.startsWith(READING)

/** One process-owned operation survives screen recreation. No service or polling. */
class InstallerApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableUi = MutableStateFlow(InstallerUiState())
    val ui = mutableUi.asStateFlow()
    private var apk: Apk? = null
    @Volatile private var parser: ApkParser? = null

    override fun onCreate() {
        super.onCreate()
        // Listed synchronously so a share arriving at startup is never swept; the debug fixture process shares this class.
        if (!isMainProcess) return
        val stale = cacheDir.listFiles { file -> file.isDirectory && file.name.startsWith("selected-") }.orEmpty()
        if (stale.isNotEmpty()) scope.launch { withContext(Dispatchers.IO) { stale.forEach { it.deleteRecursively() } } }
    }

    private val isMainProcess: Boolean
        get() = packageName == if (Build.VERSION.SDK_INT >= 28) getProcessName()
            else runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrNull()

    fun selectApk(uri: Uri, selectionId: String = UUID.randomUUID().toString()) {
        if (mutableUi.value.busy) return
        mutableUi.update { it.copy(selectionId = selectionId) }
        operation(READING) {
            val reader = ApkParser(this) { copied, total ->
                val of = total?.let { " of ${Formatter.formatShortFileSize(this, it)}" }.orEmpty()
                mutableUi.update { it.copy(stage = "$READING ${Formatter.formatShortFileSize(this, copied)}$of") }
            }
            parser = reader
            val parsed = try {
                discardSelection()
                withContext(Dispatchers.IO) { reader.read(uri) }
            } finally { parser = null }
            apk = parsed
            val size = Formatter.formatShortFileSize(this, parsed.size + parsed.expansions.sumOf { it.file.length() })
            val count = if (parsed.parts.size > 1) " · ${parsed.parts.size} APKs" else ""
            val data = if (parsed.expansions.isNotEmpty()) " · includes OBB" else ""
            mutableUi.update { it.copy(selected = SelectedApp(parsed.label, parsed.packageName, parsed.version,
                parsed.displayName, "$size$count$data", parsed.icon, installedNote(parsed.packageName, parsed.versionCode)), canInstall = true) }
        }
    }

    fun cancelRead(): Boolean {
        val active = parser ?: return false
        active.abort()
        return true
    }

    fun clearSelection() = operation("Clearing…") {
        mutableUi.update { it.copy(selectionId = null) }
        discardSelection()
    }

    private suspend fun discardSelection() {
        val previous = apk
        apk = null
        mutableUi.update { it.copy(selected = null, canInstall = false) }
        withContext(Dispatchers.IO) {
            if (previous != null && !previous.delete()) throw InstallerException(ErrorKind.CleanupFailed,
                "The selected file was cleared, but its private cache could not be fully removed.")
        }
    }

    fun install() {
        val selected = apk ?: return
        operation("Installing…") {
            try {
                val log = StringBuilder()
                val installed = withContext(Dispatchers.IO) {
                    InstallController(this@InstallerApplication, { line ->
                        if (log.length < LOG_LIMIT) log.append(line).append('\n')
                        else if (!log.endsWith(LOG_TRUNCATED)) log.append(LOG_TRUNCATED)
                    }, { stage ->
                        mutableUi.update { it.copy(stage = stage) }
                    }).install(selected, PLAY_STORE_PACKAGE)
                }
                mutableUi.update { it.copy(result = installed.summary(), outcome = installed.outcome,
                    isError = installed.outcome == InstallOutcome.Failure, log = log.toString().trimEnd()) }
            } finally {
                apk = null
                mutableUi.update { it.copy(canInstall = false) }
            }
        }
    }

    fun pickerFailed(error: Throwable) {
        inputFailed("Cannot open the file picker: ${error.message}")
    }

    fun inputFailed(message: String) {
        if (!mutableUi.value.busy) mutableUi.update { it.copy(result = message, isError = true, outcome = null, log = "") }
    }

    private fun operation(stage: String, block: suspend () -> Unit) {
        if (mutableUi.value.busy) return
        mutableUi.update { it.copy(busy = true, stage = stage, result = "", isError = false, outcome = null, log = "") }
        scope.launch {
            try {
                block()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                mutableUi.update { it.copy(result = "${(e as? InstallerException)?.kind ?: "Error"}: ${e.message}", isError = true) }
            } finally {
                mutableUi.update { it.copy(busy = false, stage = "") }
            }
        }
    }
}
