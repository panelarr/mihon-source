// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.CheckStep
import panelarr.mihon.v1.checkSourcesRequest
import panelarr.mihon.v1.setHealthCheckRequest
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.mihonsource.fixtures.TestSigningKey
import panelarr.source.v1.SourceHealthStatus
import panelarr.source.v1.listSourcesRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** On-demand source checks and the scheduled health self-check. */
class SourceHealthTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var site: FakeSite
    private var service: TestService? = null

    @BeforeEach
    fun start() {
        site = FakeSite()
        System.setProperty("panelarr.testext.baseUrl", site.baseUrl)
    }

    @AfterEach
    fun stop() {
        service?.close()
        site.close()
        System.clearProperty("panelarr.testext.baseUrl")
    }

    private fun service(interval: Duration? = null): TestService =
        TestService(dir.resolve("data"), healthCheckInterval = interval).also {
            service = it
            it.install(TestExtensions.lib16)
        }

    private fun TestService.sourceId(lang: String) =
        runBlocking {
            sources.listSources(listSourcesRequest { this.lang = lang })
        }.sourcesList.single().id

    @Test
    fun `a full check walks list, series, chapters, pages and image`() {
        val svc = service()
        val en = svc.sourceId("en")
        val result =
            runBlocking {
                svc.admin.checkSources(
                    checkSourcesRequest {
                        sourceIds += en
                        steps +=
                            listOf(
                                CheckStep.CHECK_STEP_POPULAR,
                                CheckStep.CHECK_STEP_LATEST,
                                CheckStep.CHECK_STEP_SEARCH,
                                CheckStep.CHECK_STEP_IMAGE,
                            )
                    },
                )
            }.resultsList.single()
        assertTrue(result.ok, result.toString())
        // IMAGE pulls in PAGES and CHAPTERS; every step ran.
        assertEquals(
            listOf("POPULAR", "LATEST", "SEARCH", "CHAPTERS", "PAGES", "IMAGE"),
            result.stepsList.map { it.step.name.removePrefix("CHECK_STEP_") },
        )
        assertEquals("2 series", result.stepsList.first().detail)
        assertTrue(
            site
                .requestsTo("/api/search")
                .single()
                .url
                .queryParameter("q")!!
                .startsWith("Popular"),
        )
        assertTrue("image/png" in result.stepsList.last().detail)

        val health = runBlocking { svc.sources.listSources(listSourcesRequest { lang = "en" }) }.sourcesList.single().health
        assertEquals(SourceHealthStatus.SOURCE_HEALTH_STATUS_OK, health.status)
        assertTrue(health.hasCheckedAt())
        assertTrue(Regex("""mihon_source_health\{source="$en"} 1.0""").containsMatchIn(String(svc.get("/metrics").body())))
    }

    @Test
    fun `a failing source records the reason and skips what depends on it`() {
        val svc = service()
        site.override = { request -> if (request.url.encodedPath == "/api/popular") MockResponse.Builder().code(503).build() else null }
        val result =
            runBlocking {
                svc.admin.checkSources(checkSourcesRequest { steps += CheckStep.CHECK_STEP_CHAPTERS })
            }.resultsList
        assertEquals(2, result.size)
        result.forEach { check ->
            assertEquals(false, check.ok)
            assertEquals("SOURCE_DOWN", check.stepsList.first().reason)
            assertTrue(check.stepsList.single { it.step == CheckStep.CHECK_STEP_CHAPTERS }.skipped)
        }
        val health = runBlocking { svc.sources.listSources(listSourcesRequest {}) }.sourcesList.map { it.health }
        assertTrue(health.all { it.status == SourceHealthStatus.SOURCE_HEALTH_STATUS_FAILING && it.reason == "SOURCE_DOWN" })
    }

    @Test
    fun `lib 1_4 sources can be checked too`() {
        val svc = service()
        svc.install(TestExtensions.lib14Apk(TestSigningKey(), dir.resolve("lib14.apk")))
        val id = runBlocking { svc.sources.listSources(listSourcesRequest {}) }.sourcesList.single { it.name == "Lib14 Test" }.id
        val result =
            runBlocking {
                svc.admin.checkSources(
                    checkSourcesRequest {
                        sourceIds += id
                        steps += CheckStep.CHECK_STEP_IMAGE
                    },
                )
            }.resultsList.single()
        assertTrue(result.ok, result.toString())
    }

    @Test
    fun `scheduled checks cover enabled sources only`() {
        val svc = service(interval = Duration.ofSeconds(2))
        val en = svc.sourceId("en")
        val fr = svc.sourceId("fr")
        runBlocking {
            svc.admin.setHealthCheck(
                setHealthCheckRequest {
                    sourceId = fr
                    enabled = false
                },
            )
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline &&
            site.requestsTo("/api/popular").none { it.url.queryParameter("lang") == "en" }
        ) {
            Thread.sleep(200)
        }
        Thread.sleep(2_500)
        val health = runBlocking { svc.sources.listSources(listSourcesRequest {}) }.sourcesList.associate { it.id to it.health.status }
        assertEquals(SourceHealthStatus.SOURCE_HEALTH_STATUS_OK, health[en])
        assertEquals(SourceHealthStatus.SOURCE_HEALTH_STATUS_UNSPECIFIED, health[fr])
        assertTrue(site.requestsTo("/api/popular").none { it.url.queryParameter("lang") == "fr" })
    }
}
