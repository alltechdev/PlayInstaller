// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class InstallerColors(
    val background: Color, val surface: Color, val foreground: Color,
    val muted: Color, val outline: Color, val action: Color, val actionText: Color,
    val error: Color, val warning: Color
) {
    fun resultColor(state: InstallerUiState): Color = when {
        state.isError -> error
        state.outcome?.warning == true -> warning
        else -> foreground
    }
}

@Composable
internal fun installerColors(): InstallerColors {
    val dark = isSystemInDarkTheme()
    return InstallerColors(
        background = if (dark) Color(0xFF121212) else Color(0xFFFAFAFA),
        surface = if (dark) Color(0xFF1B1B1B) else Color.White,
        foreground = if (dark) Color(0xFFE8E8E8) else Color(0xFF202020),
        muted = if (dark) Color(0xFFAAAAAA) else Color(0xFF707070),
        outline = if (dark) Color(0xFF383838) else Color(0xFFE2E2E2),
        action = if (dark) Color(0xFFB7C8F1) else Color(0xFF405A96),
        actionText = if (dark) Color(0xFF1C2437) else Color.White,
        error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
        warning = if (dark) Color(0xFFFFD180) else Color(0xFF8A4B00)
    )
}

@Composable
internal fun InstallerButton(
    label: String, enabled: Boolean, background: Color, foreground: Color,
    border: Color, onClick: () -> Unit, compact: Boolean = false,
    shape: Shape = RoundedCornerShape(if (compact) 12.dp else 14.dp)
) {
    Box(
        modifier = (if (compact) Modifier else Modifier.fillMaxWidth())
            .defaultMinSize(minHeight = if (compact) 48.dp else 52.dp)
            .clip(shape)
            .background(background.copy(alpha = if (enabled) 1f else 0.45f))
            .border(1.dp, border.copy(alpha = if (enabled) 1f else 0.45f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 18.dp else 20.dp, vertical = if (compact) 12.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = TextStyle(
            color = foreground.copy(alpha = if (enabled) 1f else 0.5f),
            fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Medium,
            textAlign = if (compact) TextAlign.Unspecified else TextAlign.Center
        ))
    }
}

@Composable
internal fun InstalledNoteText(note: InstalledNote, colors: InstallerColors, style: TextStyle) {
    BasicText(note.text, style = if (note.downgrade) style.copy(color = colors.warning) else style)
}

@Composable
internal fun InstallResultText(state: InstallerUiState, colors: InstallerColors, style: TextStyle,
    modifier: Modifier = Modifier) {
    SelectionContainer {
        BasicText(state.result, style = style.copy(color = colors.resultColor(state)), modifier = modifier)
    }
}
