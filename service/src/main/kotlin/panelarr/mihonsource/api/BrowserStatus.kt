// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

import com.google.protobuf.Duration
import panelarr.mihon.v1.Browser
import panelarr.mihon.v1.TestBrowserResponse
import panelarr.mihon.v1.browser
import panelarr.mihon.v1.testBrowserResponse
import panelarr.mihonsource.webview.CdpBrowser
import panelarr.mihonsource.webview.test

/** The browser service as the admin API shows it: checked when read (at most every few seconds). */
internal suspend fun browserStatus(browser: CdpBrowser?): Browser {
    if (browser == null) return browser { configured = false }
    val status = browser.check()
    return browser {
        configured = true
        address = browser.address
        up = status.up
        status.browser?.let { product = it }
        status.error?.let { error = it }
        checkedAt = status.checkedAt.toTimestamp()
        pagesOpen = browser.openPages()
        maxPages = browser.maxPages
        contextsOpen = browser.openContexts()
        loadTimeout = browser.loadTimeout.toProto()
        idleTimeout = browser.idleTimeout.toProto()
    }
}

/** Runs [CdpBrowser.test]; without a browser that's a failed precondition. */
internal suspend fun runBrowserTest(browser: CdpBrowser?): TestBrowserResponse {
    checkNotNull(browser) { "no browser service is configured: set MIHON_WEBVIEW_URL to a headless Chromium" }
    val result = browser.test()
    return testBrowserResponse {
        ok = result.ok
        message = result.message
        result.browser?.let { product = it }
        latency = result.latency.toProto()
    }
}

private fun java.time.Duration.toProto(): Duration =
    Duration
        .newBuilder()
        .setSeconds(seconds)
        .setNanos(nano)
        .build()
