// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Checks the browser end to end, as a WebView would use it: connects (now, not from a
 * cached check), opens a page in a context of its own, runs a script and closes both.
 */
suspend fun CdpBrowser.test(): CdpBrowser.TestResult {
    val started = System.nanoTime()
    val since = { Duration.ofNanos(System.nanoTime() - started) }
    val status = check(fresh = true)
    if (!status.up) return CdpBrowser.TestResult(false, status.error ?: "the browser is unreachable", latency = since())
    val key = "test-${java.util.UUID.randomUUID()}"
    return try {
        val answer = withPage(key) { page -> page.evaluate("6 * 7") }
        if (answer?.toString() == "42") {
            CdpBrowser.TestResult(true, "opened a page and ran a script", status.browser, since())
        } else {
            CdpBrowser.TestResult(false, "the browser ran a test script but answered unexpectedly", status.browser, since())
        }
    } catch (e: WebViewException) {
        CdpBrowser.TestResult(false, e.message ?: "the test page failed", status.browser, since())
    } finally {
        withContext(NonCancellable) { runCatching { closeContext(key) } }
    }
}
