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
    val log: String = "",
    val allowDowngrade: Boolean = false,
    val queue: List<QueuedFile> = emptyList(),
    val queueIndex: Int = -1
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
    private var next: (() -> Unit)? = null

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

    fun selectApks(uris: List<Uri>, selectionId: String = UUID.randomUUID().toString()) {
        if (mutableUi.value.busy || uris.isEmpty()) return
        if (uris.size == 1) return selectApk(uris.single(), selectionId)
        mutableUi.update { it.copy(queue = uris.mapIndexed { index, uri -> QueuedFile(uri.toString(), "File ${index + 1}") }, queueIndex = 0) }
        selectApk(uris.first(), selectionId)
    }

    fun selectApk(uri: Uri, selectionId: String = UUID.randomUUID().toString()) {
        if (mutableUi.value.busy) return
        mutableUi.update { it.copy(selectionId = selectionId, allowDowngrade = false, queue = if (it.batch) it.queue else emptyList(), queueIndex = if (it.batch) it.queueIndex else -1) }
        operation(READING) {
            mark(QueueStatus.Reading)
            val reader = ApkParser(this) { copied, total ->
                val of = total?.let { " of ${Formatter.formatShortFileSize(this, it)}" }.orEmpty()
                mutableUi.update { it.copy(stage = "$READING ${Formatter.formatShortFileSize(this, copied)}$of") }
            }
            parser = reader
            val parsed = try {
                discardSelection()
                withContext(Dispatchers.IO) { reader.read(uri) }
            } catch (e: CancellationException) {
                mutableUi.update { it.copy(queue = emptyList(), queueIndex = -1) }
                throw e
            } catch (e: Exception) {
                if (!mutableUi.value.batch) throw e
                mark(QueueStatus.Failed, "${(e as? InstallerException)?.kind ?: "Error"}: ${e.message}")
                next = ::advance
                return@operation
            } finally { parser = null }
            apk = parsed
            val size = Formatter.formatShortFileSize(this, parsed.size + parsed.expansions.sumOf { it.file.length() })
            val count = if (parsed.parts.size > 1) " · ${parsed.parts.size} APKs" else ""
            val data = if (parsed.expansions.isNotEmpty()) " · includes OBB" else ""
            mark(QueueStatus.Ready, name = parsed.label)
            mutableUi.update { it.copy(selected = SelectedApp(parsed.label, parsed.packageName, parsed.version,
                parsed.displayName, "$size$count$data", parsed.icon, installedNote(parsed.packageName, parsed.versionCode, parsed.signers)), canInstall = true) }
        }
    }

    fun skip() {
        if (mutableUi.value.busy || !mutableUi.value.batch) return
        mark(QueueStatus.Skipped)
        advance()
    }

    private fun mark(status: QueueStatus, detail: String = "", name: String? = null) = mutableUi.update { state ->
        val item = state.current ?: return@update state
        state.copy(queue = state.queue.toMutableList().apply { set(state.queueIndex, item.copy(status = status, detail = detail, name = name ?: item.name)) })
    }

    private fun advance() {
        val state = mutableUi.value
        val index = state.queueIndex + 1
        if (index < state.queue.size) {
            mutableUi.update { it.copy(queueIndex = index) }
            selectApk(Uri.parse(state.queue[index].uri), state.selectionId ?: UUID.randomUUID().toString())
        } else operation("Finishing…") {
            discardSelection()
            mutableUi.update { it.copy(result = it.queue.summary(), outcome = null,
                isError = it.queue.none { item -> item.status == QueueStatus.Installed } && it.queue.any { item -> item.status == QueueStatus.Failed }) }
        }
    }

    fun setAllowDowngrade(allow: Boolean) = mutableUi.update { it.copy(allowDowngrade = allow) }

    fun uninstallInstalled() {
        val selected = apk ?: return
        operation("Uninstalling…") {
            withContext(Dispatchers.IO) { InstallController(this@InstallerApplication, {}, {}).uninstall(selected.packageName) }
            mutableUi.update { state -> state.copy(selected = state.selected?.copy(installed = installedNote(selected.packageName, selected.versionCode, selected.signers))) }
        }
    }

    fun cancelRead(): Boolean {
        val active = parser ?: return false
        active.abort()
        return true
    }

    fun clearSelection() = operation("Clearing…") {
        mutableUi.update { it.copy(selectionId = null, queue = emptyList(), queueIndex = -1) }
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
            mark(QueueStatus.Installing)
            InstallService.start(this, selected.label)
            try {
                val log = StringBuilder()
                val installed = withContext(Dispatchers.IO) {
                    InstallController(this@InstallerApplication, { line ->
                        if (log.length < LOG_LIMIT) log.append(line).append('\n')
                        else if (!log.endsWith(LOG_TRUNCATED)) log.append(LOG_TRUNCATED)
                    }, { stage ->
                        mutableUi.update { it.copy(stage = stage) }
                    }).install(selected, PLAY_STORE_PACKAGE, mutableUi.value.allowDowngrade)
                }
                mutableUi.update { it.copy(result = installed.summary(), outcome = installed.outcome,
                    isError = installed.outcome == InstallOutcome.Failure, log = log.toString().trimEnd()) }
                if (mutableUi.value.batch) {
                    mark(if (installed.outcome.installed) QueueStatus.Installed else QueueStatus.Failed, installed.outcome.title)
                    next = ::advance
                }
            } finally {
                InstallService.stop(this)
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
                next?.let { next = null; it() }
            }
        }
    }
}
