// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat.check

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private val json =
    Json {
        prettyPrint = true
        encodeDefaults = false
    }

fun main(args: Array<String>) {
    val options =
        try {
            Options.parse(args.toList())
        } catch (
            @Suppress("SwallowedException") e: HelpRequested,
        ) {
            println(Options.USAGE)
            return
        } catch (e: IllegalArgumentException) {
            System.err.println("compat-check: ${e.message}\n\n${Options.USAGE}")
            exitProcess(2)
        }
    val report = runBlocking { CompatCheck(options) { println(it) }.run() }
    val out = options.out ?: defaultReportPath()
    Files.createDirectories(out.toAbsolutePath().parent)
    Files.writeString(out, json.encodeToString(Report.serializer(), report))
    println()
    println(summary(report))
    println("Report: $out")
}

/** Reports go under build/, which git ignores. */
fun defaultReportPath(): Path =
    Path.of(
        "build",
        "compat-reports",
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json",
    )

fun summary(report: Report): String =
    buildString {
        appendLine("${report.extensions.size} extension(s): ${report.loaded} loaded, ${report.failed} not loaded")
        report.extensions
            .filter { it.problem != null }
            .groupBy { it.problem }
            .forEach { (problem, items) -> appendLine("  $problem: ${items.size}") }
        if (report.checks.isNotEmpty()) {
            val sources = report.extensions.flatMap { it.sources }
            appendLine("${sources.size} source(s) checked: ${sources.count { it.ok }} ok, ${sources.count { !it.ok }} failing")
            sources
                .filter { !it.ok }
                .mapNotNull { source -> source.steps.firstOrNull { !it.ok && !it.skipped }?.let { "${it.step}: ${it.reason ?: "error"}" } }
                .groupingBy { it }
                .eachCount()
                .forEach { (failure, count) -> appendLine("  $failure: $count") }
        }
        if (needsBrowser(report)) appendLine(WEBVIEW_HINT)
    }.trimEnd()

/** Shown when sources failed only because no browser is configured. */
const val WEBVIEW_HINT =
    "WebView sources need the optional browser service: run a headless Chromium and set MIHON_WEBVIEW_URL " +
        "(see docs/configuration.md). With it configured, they run like any other source."

private fun needsBrowser(report: Report): Boolean =
    report.extensions.any { extension ->
        extension.problem == "needs webview" || extension.sources.any { source -> source.steps.any { it.reason == "NEEDS_WEBVIEW" } }
    }
