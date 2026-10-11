// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import com.google.protobuf.Struct
import com.google.protobuf.Value
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihonsource.config.AppConfig
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.source.v1.listSourcesRequest
import panelarr.source.v1.popularRequest
import panelarr.source.v1.searchRequest
import panelarr.source.v1.setSettingsRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The result cache through the API: hits, bypass, invalidation and metrics. */
class CacheTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var site: FakeSite
    private lateinit var service: TestService
    private lateinit var en: String

    @BeforeEach
    fun start() {
        site = FakeSite()
        System.setProperty("panelarr.testext.baseUrl", site.baseUrl)
        service = TestService(dir.resolve("data"), cacheTtls = AppConfig.DEFAULT_CACHE_TTLS)
        service.install(TestExtensions.lib16)
        en = runBlocking { service.sources.listSources(listSourcesRequest { lang = "en" }) }.sourcesList.single().id
    }

    @AfterEach
    fun stop() {
        service.close()
        site.close()
        System.clearProperty("panelarr.testext.baseUrl")
    }

    private fun popular() =
        runBlocking {
            service.sources.popular(
                popularRequest {
                    sourceId = en
                    page = 1
                },
            )
        }

    private fun search(query: String) =
        runBlocking {
            service.sources.search(
                searchRequest {
                    sourceId = en
                    this.query = query
                    page = 1
                },
            )
        }

    private fun popularRequests() = site.requestsTo("/api/popular").size

    private fun httpPopular(noCache: Boolean): Int {
        val request =
            HttpRequest
                .newBuilder(URI.create(service.base + "/v1/sources/$en/popular?page=1"))
                .header("Authorization", "Bearer ${TestService.ADMIN}")
                .apply { if (noCache) header("Cache-Control", "no-cache") }
                .GET()
                .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    @Test
    fun `repeated calls are served from the cache until bypassed or invalidated`() {
        repeat(3) { popular() }
        assertEquals(1, popularRequests())
        // The same call over HTTP hits the same cache entry.
        assertEquals(200, httpPopular(noCache = false))
        assertEquals(1, popularRequests())

        assertEquals(200, httpPopular(noCache = true))
        assertEquals(2, popularRequests())

        runBlocking {
            service.sources.setSettings(
                setSettingsRequest {
                    sourceId = en
                    values = Struct.newBuilder().putFields("show_adult", Value.newBuilder().setBoolValue(true).build()).build()
                },
            )
        }
        popular()
        assertEquals(3, popularRequests())

        val metrics = String(service.get("/metrics").body())
        assertTrue(Regex("""mihon_cache_lookups_total\{operation="popular",result="hit"} [1-9]""").containsMatchIn(metrics), metrics)
    }

    @Test
    fun `search results are cached per query`() {
        search("one")
        search("two")
        search("one")
        search("two")
        assertEquals(listOf("one", "two"), site.requestsTo("/api/search").map { it.url.queryParameter("q") })
    }
}
