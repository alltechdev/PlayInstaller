// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.os.Bundle
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class ShareInstallActivity : ComponentActivity() {
    private var issue: String? = null
    private lateinit var selectionId: String
    private val ownsSelection get() = app.ui.value.selectionId == selectionId
    private val app get() = application as InstallerApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        selectionId = savedInstanceState?.getString("selectionId") ?: UUID.randomUUID().toString()
        issue = savedInstanceState?.getString("issue")
        if (savedInstanceState != null) {
            if (issue == null && !ownsSelection) {
                issue = "This file is no longer active. Open or select it again. If installation was interrupted, check the app before retrying."
            }
        } else if (app.ui.value.busy) {
            issue = "Another operation is already in progress."
        } else {
            try {
                app.selectApks(intent.sharedFileUris(), selectionId)
            } catch (e: Exception) {
                issue = "Cannot open file: ${e.message}"
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!ownsSelection || !app.ui.value.busy || app.ui.value.reading) dismiss()
            }
        })
        setContent {
            val state by app.ui.collectAsStateWithLifecycle()
            val active = state.selectionId == selectionId
            val problem = issue ?: if (!active) "This file is no longer active. Open or select it again." else null
            ShareInstallDialog(if (active) state else InstallerUiState(), problem,
                onInstall = { if (ownsSelection) app.install() },
                onOpen = ::openInstalledApp, onCopy = { copyReport(state) }, onDismiss = ::dismiss,
                onToggleReplace = { app.setReplaceInstalled(!state.replaceInstalled) },
                onUninstall = { if (ownsSelection) app.uninstallInstalled() },
                onSkip = { if (ownsSelection) app.skip() })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectionId", selectionId)
        outState.putString("issue", issue)
        super.onSaveInstanceState(outState)
    }

    private fun dismiss() {
        if (ownsSelection && !app.cancelRead() && !app.ui.value.busy && app.ui.value.selected != null) app.clearSelection()
        finish()
    }

    private fun openInstalledApp() {
        if (!ownsSelection) return
        val packageName = app.ui.value.selected?.packageName ?: return
        openInstalledApp(packageName)
    }
}

@Composable
private fun ShareInstallDialog(state: InstallerUiState, issue: String?, onInstall: () -> Unit,
    onOpen: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit, onToggleReplace: () -> Unit, onUninstall: () -> Unit,
    onSkip: () -> Unit) {
    val colors = installerColors()
    val selected = state.selected
    val ready = issue == null && !state.busy && state.canInstall && selected != null
    val installable = ready && selected?.installed?.blocks(state.replaceInstalled) != true
    val failed = issue != null || state.isError
    val result = state.result.isNotEmpty() && !state.busy
    val installed = result && state.outcome?.installed == true
    val copyable = result && issue == null && (state.isError || state.outcome?.warning == true)
    val position = if (state.batch && state.queueIndex in state.queue.indices) " (${state.queueIndex + 1} of ${state.queue.size})" else ""
    val title = when {
        issue != null -> "Can't open file"
        state.busy -> when { state.reading -> "Reading file$position"; state.stage == "Uninstalling…" -> "Uninstalling"; else -> "Installing$position" }
        ready -> "Install this app?$position"
        result && state.batch && state.selected == null -> "Batch finished"
        failed -> if (state.selected == null) "Can't open file" else "Installation failed"
        result -> state.outcome?.title ?: "Installation result"
        else -> "Open with PlayInstaller"
    }
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().clip(shape).background(colors.surface)
            .border(1.dp, colors.outline, shape).verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        BasicText(title, style = TextStyle(color = colors.foreground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
        when {
            issue != null -> BasicText(issue, style = TextStyle(color = colors.error, fontSize = 14.sp, lineHeight = 20.sp))
            state.busy -> BasicText(state.stage, style = TextStyle(color = colors.muted, fontSize = 14.sp))
            ready -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShareAppCard(selected, colors)
                selected.installed?.let { InstalledActions(it, state.replaceInstalled, true, colors, onToggleReplace, onUninstall) }
            }
            state.result.isNotEmpty() -> InstallResultText(state, colors,
                TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
        }
        FlowRow(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ready) {
                InstallerButton("Cancel", enabled = true, colors.background, colors.foreground, colors.outline, onDismiss, compact = true)
                if (state.batch) InstallerButton("Skip", enabled = true, colors.background, colors.foreground, colors.outline, onSkip, compact = true)
                InstallerButton("Install", enabled = installable, colors.action, colors.actionText, colors.action, onInstall, compact = true)
            } else if (state.reading && issue == null) {
                InstallerButton("Cancel", enabled = true, colors.background, colors.foreground, colors.outline, onDismiss, compact = true)
            } else if (!state.busy && (result || issue != null)) {
                InstallerButton("Close", enabled = true, colors.background, colors.foreground, colors.outline, onDismiss, compact = true)
                if (copyable) InstallerButton(if (state.isError) "Copy error" else "Copy details", enabled = true,
                    colors.background, colors.foreground, colors.outline, onCopy, compact = true)
                if (installed) InstallerButton("Open app", enabled = true, colors.action, colors.actionText, colors.action, onOpen, compact = true)
            }
        }
    }
}

@Composable
private fun ShareAppCard(app: SelectedApp, colors: InstallerColors) {
    val foreground = colors.foreground
    val muted = colors.muted
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = app.icon
            if (icon != null) Image(remember(icon) { icon.asImageBitmap() }, null, Modifier.size(44.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(app.name, style = TextStyle(color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText("Version ${app.version}", style = TextStyle(color = muted, fontSize = 13.sp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(app.packageName, style = TextStyle(color = muted, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText("${app.filename} · ${app.details}", style = TextStyle(color = muted, fontSize = 12.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            app.installed?.let { InstalledNoteText(it, colors, TextStyle(color = muted, fontSize = 13.sp)) }
        }
    }
}
