// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

object ShellEscaper {
    fun quote(value: String): String {
        require('\u0000' !in value) { "Shell arguments cannot contain NUL" }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
    fun command(vararg args: String) = args.joinToString(" ") { quote(it) }
}
