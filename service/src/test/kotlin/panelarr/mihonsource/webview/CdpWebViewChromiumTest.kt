// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import panelarr.mihonsource.compat.AndroidHost
import panelarr.mihonsource.compat.WebViewProvider
import panelarr.mihonsource.config.WebViewConfig
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.HeadlessChromium
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** The WebView engine's page callbacks against a real headless Chromium and a local test site. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CdpWebViewChromiumTest {
    private lateinit var chromium: HeadlessChromium
    private lateinit var site: FakeSite
    private lateinit var browser: CdpBrowser

    @BeforeAll
    fun start() {
        chromium = HeadlessChromium.start()
        site = FakeSite()
        site.override = { request ->
            if (request.url.encodedPath == "/a") {
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .body("<html><head><title>Page A</title></head><body>a</body></html>")
                    .build()
            } else {
                null
            }
        }
        browser = CdpBrowser(WebViewConfig(chromium.url, loadTimeout = Duration.ofSeconds(30)))
    }

    @AfterAll
    fun stop() {
        if (::browser.isInitialized) browser.close()
        if (::site.isInitialized) site.close()
        if (::chromium.isInitialized) chromium.close()
    }

    @AfterEach
    fun tearDown() {
        AndroidHost.webViewProvider = null
    }

    private class Recorder : WebViewClient() {
        val events = LinkedBlockingQueue<String>()

        override fun onPageStarted(
            view: WebView,
            url: String,
            favicon: Bitmap?,
        ) {
            events += "started $url"
        }

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) {
            events += "finished $url"
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            events += "error ${request.url}"
        }

        fun next(): String = events.poll(LOAD_SECONDS, TimeUnit.SECONDS) ?: fail("no callback within ${LOAD_SECONDS}s")
    }

    @Test
    fun `a failed load reports the failed URL, and nothing for the page before it`() {
        AndroidHost.webViewProvider =
            WebViewProvider { CdpWebView(it, browser, "chromium-${it.hashCode()}", Duration.ofSeconds(LOAD_SECONDS)) }
        val view = WebView(AndroidHost.application)
        val recorder = Recorder()
        view.webViewClient = recorder
        val good = "${site.baseUrl}/a"
        // Nothing listens there, so the load fails and Chromium commits its own error page.
        val failed = "http://127.0.0.1:1/"
        try {
            view.loadUrl(good)
            assertEquals("started $good", recorder.next())
            assertEquals("finished $good", recorder.next())
            assertEquals("Page A", view.title)

            view.loadUrl(failed)
            assertEquals("error $failed", recorder.next())
            assertEquals("finished $failed", recorder.next())
            // The error page's load event isn't reported as the previous page finishing again.
            assertNull(recorder.events.poll(SETTLE_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(failed, view.url)
            assertEquals(failed, view.title)

            // The next load is reported again.
            view.loadUrl(good)
            assertEquals("started $good", recorder.next())
            assertEquals("finished $good", recorder.next())
            assertEquals(good, view.url)
        } finally {
            Handler(Looper.getMainLooper()).post { view.destroy() }
        }
    }

    private companion object {
        const val LOAD_SECONDS = 30L

        /** Long enough for Chromium's error page to load and fire its events. */
        const val SETTLE_MILLIS = 1500L
    }
}
