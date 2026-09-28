// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int) {
    val output: String get() = listOf(stdout.trimEnd(), stderr.trimEnd()).filter { it.isNotEmpty() }.joinToString("\n")
    val ok: Boolean get() = exitCode == 0
}

enum class ErrorKind {
    RootUnavailable, ApkNotFound, ApkUnreadable, ApkParseFailed, InstallerPackageNotFound,
    SessionCreationFailed, SessionWriteFailed, SessionCommitFailed, CleanupFailed, CommandFailed
}
class InstallerException(val kind: ErrorKind, message: String) : Exception(message)

interface CommandShell : Closeable {
    fun execute(vararg args: String, timeoutMs: Long = 120_000): ShellResult
}

/** One su process per operation; separate, bounded stdout/stderr and random exit frames. */
class RootShell(private val launcher: () -> Process = { ProcessBuilder("su").start() }) : CommandShell {
    private data class Event(val stream: Int, val line: String?, val error: String? = null)
    private val events = LinkedBlockingQueue<Event>(2048)
    private var process: Process? = null
    private var readers: List<Thread> = emptyList()
    private var closed = false

    fun open(): RootShell {
        try {
            process = launcher()
            readers = listOf(process!!.inputStream, process!!.errorStream).mapIndexed { index, input ->
                Thread({
                    try {
                        // Read characters, not unbounded readLine() on untrusted command output.
                        input.bufferedReader().use { reader ->
                            val line = StringBuilder()
                            while (true) {
                                val ch = reader.read()
                                if (ch < 0) break
                                if (ch == 10 || line.length >= 8192) {
                                    events.put(Event(index, line.toString())); line.setLength(0)
                                }
                                if (ch != 10) line.append(ch.toChar())
                            }
                            if (line.isNotEmpty()) events.put(Event(index, line.toString()))
                        }
                        events.put(Event(index, null))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (e: Exception) {
                        events.offer(Event(index, null, e.message))
                    }
                }, "root-output-$index").apply { isDaemon = true; start() }
            }
            val id = execute("id", "-u", timeoutMs = 30_000)
            if (!id.ok || id.stdout.trim() != "0") {
                throw InstallerException(ErrorKind.RootUnavailable, "Root access is required. Grant su access and retry.\n${id.output}")
            }
            return this
        } catch (e: Exception) {
            close()
            if (e is InstallerException) throw e
            throw InstallerException(ErrorKind.RootUnavailable, "Cannot obtain root: ${e.message}")
        }
    }

    @Synchronized
    override fun execute(vararg args: String, timeoutMs: Long): ShellResult {
        check(!closed && process != null) { "Root shell is closed" }
        val token = "ROOT_${UUID.randomUUID().toString().replace("-", "")}_"
        val script = "${ShellEscaper.command(*args)}\nri_status=$?\nprintf '\\n${token}%s\\n' \"\$ri_status\"\nprintf '\\n${token}%s\\n' \"\$ri_status\" >&2\n"
        val out = StringBuilder()
        val err = StringBuilder()
        var outCode: Int? = null
        var errCode: Int? = null
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            process!!.outputStream.apply { write(script.toByteArray(Charsets.UTF_8)); flush() }
            while (outCode == null || errCode == null) {
                val remaining = deadline - System.nanoTime()
                val event = if (remaining > 0) events.poll(remaining, TimeUnit.NANOSECONDS) else null
                if (event == null) throw InstallerException(ErrorKind.CommandFailed,
                    "Command timed out after ${timeoutMs / 1000}s; Android may still finish it. Check package state before retrying.\n$out\n$err")
                val line = event.line ?: throw InstallerException(ErrorKind.CommandFailed, "Root shell exited unexpectedly. ${event.error.orEmpty()}\n$out\n$err")
                if (line.startsWith(token)) {
                    val code = line.removePrefix(token).trim().toIntOrNull()
                        ?: throw IllegalStateException("Invalid shell status")
                    if (event.stream == 0) outCode = code else errCode = code
                } else {
                    val buffer = if (event.stream == 0) out else err
                    if (buffer.length < 512 * 1024) buffer.append(line).append('\n')
                }
            }
            check(outCode == errCode) { "Mismatched shell status" }
            return ShellResult(out.toString(), err.toString(), outCode)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val p = process ?: return
        runCatching { p.outputStream.close() }
        p.destroy()
        if (!runCatching { p.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) p.destroyForcibly()
        readers.forEach { it.interrupt() }
        runCatching { p.inputStream.close() }
        runCatching { p.errorStream.close() }
        readers.forEach { runCatching { it.join(500) } }
    }
}
