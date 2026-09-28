// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import androidx.core.graphics.createBitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as InstallerApplication
        setContent {
            val state by app.ui.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) app.selectApk(uri)
            }
            InstallerScreen(
                state = state,
                onSelect = {
                    runCatching { picker.launch(arrayOf("*/*")) }
                        .onFailure { app.pickerFailed(it) }
                },
                onInstall = app::install,
                onOpen = { state.selected?.packageName?.let { openInstalledApp(it) } },
                onClear = app::clearSelection,
                onCopyResult = { copyReport(state) },
                onCancelRead = { app.cancelRead() }
            )
        }
    }

}

@Composable
private fun InstallerScreen(state: InstallerUiState, onSelect: () -> Unit, onInstall: () -> Unit,
    onClear: () -> Unit, onCopyResult: () -> Unit, onOpen: () -> Unit, onCancelRead: () -> Unit) {
    val colors = installerColors()
    val shape = RoundedCornerShape(14.dp)
    val body = TextStyle(color = colors.foreground, fontSize = 15.sp, lineHeight = 22.sp)
    Box(modifier = Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.safeDrawingPadding().widthIn(max = 480.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            val selected = state.selected
            if (selected == null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val resources = LocalResources.current
                    val configuration = LocalConfiguration.current
                    val launcherIcon = remember(resources, configuration) {
                        runCatching {
                            val drawable = resources.getDrawable(R.mipmap.ic_launcher, null) ?: return@runCatching null
                            val pixels = (64 * resources.displayMetrics.density).toInt().coerceIn(64, 192)
                            createBitmap(pixels, pixels).also { bitmap ->
                                drawable.setBounds(0, 0, pixels, pixels)
                                drawable.draw(android.graphics.Canvas(bitmap))
                            }.asImageBitmap()
                        }.getOrNull()
                    }
                    if (launcherIcon != null) Image(launcherIcon, contentDescription = null, modifier = Modifier.size(76.dp))
                    BasicText("APK  ·  APKS  ·  APKM  ·  XAPK", style = body.copy(color = colors.muted, fontSize = 13.sp))
                    InstallerButton("Select file", !state.busy, colors.surface, colors.foreground, colors.outline, onSelect, shape = shape)
                }
            } else {
                AppCard(selected, colors, body, !state.busy, onClear, shape)
            }
            if (selected != null) {
                InstallerButton("Install", state.canInstall && !state.busy, colors.action, colors.actionText, colors.action,
                    if (state.busy) ({}) else onInstall, shape = shape)
            }
            if (state.busy) {
                BasicText(state.stage, style = body.copy(color = colors.muted), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.reading) InstallerButton("Cancel", true, colors.background, colors.foreground, colors.outline, onCancelRead, compact = true)
            }
            if (state.result.isNotEmpty()) {
                InstallResultText(state, colors, body,
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite })
                if (!state.busy && state.outcome?.installed == true && selected != null) {
                    InstallerButton("Open app", true, colors.action, colors.actionText, colors.action, onOpen, shape = shape)
                }
                if (state.isError || state.outcome?.warning == true) {
                    InstallerButton(if (state.isError) "Copy error" else "Copy details", !state.busy,
                        colors.background, colors.foreground, colors.outline, onCopyResult, shape = shape)
                }
            }
        }
    }
}

@Composable
private fun AppCard(app: SelectedApp, colors: InstallerColors, body: TextStyle, enabled: Boolean, onClear: () -> Unit, shape: Shape) {
    val foreground = colors.foreground
    val secondary = body.copy(color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, colors.outline, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = app.icon
            if (icon != null) {
                Image(remember(icon) { icon.asImageBitmap() }, contentDescription = null, modifier = Modifier.size(48.dp))
            } else {
                Box(Modifier.size(48.dp).background(foreground.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    BasicText("APK", style = secondary.copy(fontWeight = FontWeight.Bold))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(app.name, style = body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText("Version ${app.version}", style = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicText("Clear", style = secondary.copy(color = foreground.copy(alpha = if (enabled) 0.8f else 0.3f)),
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClear)
                    .padding(horizontal = 6.dp, vertical = 14.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(app.packageName, style = secondary.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(app.filename, style = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                BasicText(app.details, style = secondary.copy(fontSize = 12.sp))
            }
            app.installed?.let { InstalledNoteText(it, colors, secondary) }
        }
    }
}
