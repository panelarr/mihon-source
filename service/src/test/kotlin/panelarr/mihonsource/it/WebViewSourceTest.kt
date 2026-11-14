// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import com.google.rpc.ErrorInfo
import io.grpc.StatusException
import io.grpc.protobuf.StatusProto
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.CheckStep
import panelarr.mihon.v1.checkSourcesRequest
import panelarr.mihonsource.compat.check.CompatCheck
import panelarr.mihonsource.compat.check.Options
import panelarr.mihonsource.compat.check.Status
import panelarr.mihonsource.compat.check.WEBVIEW_HINT
import panelarr.mihonsource.compat.check.summary
import panelarr.mihonsource.config.WebViewConfig
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.FixtureStore
import panelarr.mihonsource.fixtures.HeadlessChromium
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.mihonsource.fixtures.TestSigningKey
import panelarr.source.v1.SourceCapability
import panelarr.source.v1.getSeriesRequest
import panelarr.source.v1.listChaptersRequest
import panelarr.source.v1.listPagesRequest
import panelarr.source.v1.listSourcesRequest
import panelarr.source.v1.popularRequest
import panelarr.source.v1.searchRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A source that drives a WebView, against a real headless Chromium and a local test site:
 * every call goes through the `android.webkit` shims and the browser.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebViewSourceTest {
    private lateinit var chromium: HeadlessChromium
    private lateinit var site: FakeSite
    private lateinit var service: TestService
    private lateinit var store: FixtureStore
    private lateinit var id: String

    @BeforeAll
    fun start(
        @TempDir dir: Path,
    ) {
        chromium = HeadlessChromium.start()
        site = FakeSite()
        System.setProperty("panelarr.testext.baseUrl", site.baseUrl)
        service = TestService(dir.resolve("data"), webView = WebViewConfig(chromium.url, loadTimeout = Duration.ofSeconds(30)))
        // Installed from a store by the compatibility checker, as a hoster would try it out.
        val key = TestSigningKey()
        store = FixtureStore()
        val jar = TestExtensions.signedJar(TestExtensions.webview, key, dir.resolve("webview.jar"))
        store.publish(
            key,
            listOf(
                FixtureStore.Offer(
                    TestExtensions.WEBVIEW_PKG,
                    "WebView Test",
                    "1.6.1",
                    106001,
                    "1.6",
                    Files.readAllBytes(jar),
                    asJar = true,
                ),
            ),
        )
        runBlocking { CompatCheck(compatOptions(emptyList())).run() }
        id = runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesList.single { it.name == "WebView Test" }.id
    }

    @AfterAll
    fun stop() {
        if (::service.isInitialized) service.close()
        if (::store.isInitialized) store.close()
        if (::site.isInitialized) site.close()
        if (::chromium.isInitialized) chromium.close()
        System.clearProperty("panelarr.testext.baseUrl")
    }

    private val api get() = service.sources

    private fun compatOptions(checks: List<String>) =
        Options(
            url = service.base,
            token = TestService.ADMIN,
            store = store.url("/store/repo.json"),
            selection = Options.Selection.Packages(listOf(TestExtensions.WEBVIEW_PKG)),
            checks = checks,
            keep = true,
        )

    @Test
    fun `the source is flagged as a WebView source`() {
        val source = runBlocking { api.listSources(listSourcesRequest {}) }.sourcesList.single { it.id == id }
        assertTrue(SourceCapability.SOURCE_CAPABILITY_NEEDS_WEBVIEW in source.capabilitiesList)
    }

    @Test
    fun `popular reads what the page's script built, through evaluateJavascript`() {
        val popular =
            runBlocking {
                api.popular(
                    popularRequest {
                        sourceId = id
                        page = 1
                    },
                )
            }
        assertEquals(listOf("web-1-1", "web-1-2"), popular.seriesList.map { it.key })
        assertEquals(listOf("Web 1 A", "Web 1 B"), popular.seriesList.map { it.title })
        assertTrue(popular.hasNextPage)
        // The settings' user agent reached the site.
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Test) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36",
            site.requestsTo("/web/popular").last().headers["User-Agent"],
        )
        // shouldOverrideUrlLoading kept the page's own redirect from leaving.
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(0, site.requestsTo("/web/trap").size)
    }

    @Test
    fun `search gets its result from the page through a JavaScript interface`() {
        val found =
            runBlocking {
                api.search(
                    searchRequest {
                        sourceId = id
                        query = "Web 1 A"
                        page = 1
                    },
                )
            }
        assertEquals(listOf("Found: Web 1 A"), found.seriesList.map { it.title })
    }

    @Test
    fun `details use extra headers and a script the extension serves`() {
        val series =
            runBlocking {
                api.getSeries(
                    getSeriesRequest {
                        sourceId = id
                        key = "web-1-1"
                    },
                )
            }.series
        assertEquals("Web series web-1-1", series.title)
        assertEquals(listOf("Web Author"), series.authorsList)
        assertEquals("Rendered by script", series.description)
        assertEquals("webview", site.requestsTo("/web/series").last().headers["X-Fixture"])
        // shouldInterceptRequest answered it: the site never saw the request.
        assertEquals(0, site.requestsTo("/web/needs.js").size)
    }

    @Test
    fun `chapters come from a page shown with loadDataWithBaseURL`() {
        val chapters =
            runBlocking {
                api.listChapters(
                    listChaptersRequest {
                        sourceId = id
                        seriesKey = "web-1-1"
                    },
                )
            }.chaptersList
        assertEquals(listOf("web-1-1-c2", "web-1-1-c1"), chapters.map { it.key })
        assertEquals(listOf("Web chapter 2", "Web chapter 1"), chapters.map { it.name })
        // The page itself was never fetched: the extension supplied it for the base URL.
        assertEquals(0, site.requestsTo("/web/").size)
    }

    @Test
    fun `pages use a cookie the page set, read through CookieManager`() {
        val pages =
            runBlocking {
                api.listPages(
                    listPagesRequest {
                        sourceId = id
                        chapterKey = "web-1-1-c1"
                    },
                )
            }.pagesList
        assertEquals(2, pages.size)
        assertEquals("tok-web-1-1-c1", site.requestsTo("/web/api/pages").last().headers["X-Reader-Token"])
    }

    @Test
    fun `a page that can't load reaches the extension as onReceivedError`() {
        val error =
            assertFailsWith<StatusException> {
                runBlocking {
                    api.getSeries(
                        getSeriesRequest {
                            sourceId = id
                            key = "unreachable"
                        },
                    )
                }
            }
        val info =
            StatusProto
                .fromThrowable(error)!!
                .detailsList
                .single { it.`is`(ErrorInfo::class.java) }
                .unpack(ErrorInfo::class.java)
        assertEquals("SOURCE_DOWN", info.reason)
        assertContains(error.status.description.orEmpty(), "the page failed")
    }

    @Test
    fun `checks find the source runnable`() {
        val result =
            runBlocking {
                service.admin.checkSources(
                    checkSourcesRequest {
                        sourceIds += id
                        steps += listOf(CheckStep.CHECK_STEP_POPULAR, CheckStep.CHECK_STEP_SEARCH, CheckStep.CHECK_STEP_IMAGE)
                    },
                )
            }.resultsList.single()
        assertTrue(result.ok, result.toString())
        assertEquals(
            listOf("POPULAR", "SEARCH", "CHAPTERS", "PAGES", "IMAGE"),
            result.stepsList.map { it.step.name.removePrefix("CHECK_STEP_") },
        )
    }

    @Test
    fun `the compatibility checker reports the source as runnable`() {
        val report = runBlocking { CompatCheck(compatOptions(listOf("details", "pages"))).run() }
        val extension = report.extensions.single()
        assertEquals(Status.LOADED, extension.status)
        assertTrue(extension.sources.single().ok, extension.toString())
        assertFalse(WEBVIEW_HINT in summary(report))
    }

    private companion object {
        /** Long enough for a redirect the page starts after loading to reach the site, were it allowed. */
        const val SETTLE_MILLIS = 500L
    }
}
