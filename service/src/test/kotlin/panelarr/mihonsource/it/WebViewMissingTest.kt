// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.CheckStep
import panelarr.mihon.v1.checkSourcesRequest
import panelarr.mihonsource.compat.check.CompatCheck
import panelarr.mihonsource.compat.check.Options
import panelarr.mihonsource.compat.check.WEBVIEW_HINT
import panelarr.mihonsource.compat.check.summary
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.FixtureStore
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.mihonsource.fixtures.TestSigningKey
import panelarr.mihonsource.mihon.SourceProber
import panelarr.source.v1.listSourcesRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Without the browser service, a WebView source says what it needs and how to get it. */
class WebViewMissingTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var site: FakeSite
    private lateinit var service: TestService

    @BeforeEach
    fun start() {
        site = FakeSite()
        System.setProperty("panelarr.testext.baseUrl", site.baseUrl)
        service = TestService(dir.resolve("data"))
    }

    @AfterEach
    fun stop() {
        service.close()
        site.close()
        System.clearProperty("panelarr.testext.baseUrl")
    }

    @Test
    fun `checks report NEEDS_WEBVIEW with a hint`() {
        service.install(TestExtensions.webview)
        val id = runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesList.single().id
        val result =
            runBlocking {
                service.admin.checkSources(
                    checkSourcesRequest {
                        sourceIds += id
                        steps += CheckStep.CHECK_STEP_POPULAR
                    },
                )
            }.resultsList.single()
        assertFalse(result.ok)
        val step = result.stepsList.single()
        assertEquals(SourceProber.NEEDS_WEBVIEW, step.reason)
        assertContains(step.message, "MIHON_WEBVIEW_URL")
        // Nothing reached the site: without a browser the page is never loaded.
        assertEquals(0, site.requests.size)
    }

    @Test
    fun `other sources keep their own failure reasons`() {
        service.install(TestExtensions.lib16)
        site.override = {
            mockwebserver3.MockResponse
                .Builder()
                .code(503)
                .build()
        }
        val id = runBlocking { service.sources.listSources(listSourcesRequest { lang = "en" }) }.sourcesList.single().id
        val step =
            runBlocking {
                service.admin.checkSources(
                    checkSourcesRequest {
                        sourceIds += id
                        steps += CheckStep.CHECK_STEP_POPULAR
                    },
                )
            }.resultsList
                .single()
                .stepsList
                .single()
        assertEquals("SOURCE_DOWN", step.reason)
    }

    @Test
    fun `the compatibility checker says how to enable the browser`() {
        val key = TestSigningKey()
        FixtureStore().use { store ->
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
            val report =
                runBlocking {
                    CompatCheck(
                        Options(
                            url = service.base,
                            token = TestService.ADMIN,
                            store = store.url("/store/repo.json"),
                            checks = listOf("popular"),
                        ),
                    ).run()
                }
            val source =
                report.extensions
                    .single()
                    .sources
                    .single()
            assertFalse(source.ok)
            assertEquals(SourceProber.NEEDS_WEBVIEW, source.steps.single().reason)
            val text = summary(report)
            assertTrue("popular: NEEDS_WEBVIEW: 1" in text, text)
            assertTrue(WEBVIEW_HINT in text, text)
        }
    }
}
