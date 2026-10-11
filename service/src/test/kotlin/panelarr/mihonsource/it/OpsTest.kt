// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.MihonClientsServiceGrpcKt.MihonClientsServiceCoroutineStub
import panelarr.mihon.v1.createClientRequest
import panelarr.mihonsource.auth.Scopes
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.source.v1.listSourcesRequest
import panelarr.source.v1.popularRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Health endpoints and metrics. */
class OpsTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var site: FakeSite
    private lateinit var service: TestService

    @BeforeEach
    fun start() {
        site = FakeSite()
        System.setProperty("panelarr.testext.baseUrl", site.baseUrl)
        service = TestService(dir.resolve("data"), metricsPort = 0)
    }

    @AfterEach
    fun stop() {
        service.close()
        site.close()
        System.clearProperty("panelarr.testext.baseUrl")
    }

    private fun key(scope: String): String =
        runBlocking {
            service
                .stub<MihonClientsServiceCoroutineStub>()
                .createClient(
                    createClientRequest {
                        name = scope
                        scopes += scope
                    },
                ).apiKey
        }

    @Test
    fun `health endpoints need no credentials`() {
        assertEquals(200, service.get("/healthz", token = null).statusCode())
        val ready = service.get("/readyz", token = null)
        assertEquals(200, ready.statusCode())
        assertTrue("ready" in String(ready.body()))
    }

    @Test
    fun `metrics need the metrics scope on the API port`() {
        assertEquals(401, service.get("/metrics", token = null).statusCode())
        assertEquals(403, service.get("/metrics", token = key(Scopes.SOURCES_READ)).statusCode())
        assertEquals(200, service.get("/metrics", token = key(Scopes.METRICS)).statusCode())
        assertEquals(200, service.get("/metrics").statusCode())
    }

    @Test
    fun `source calls are measured by source, operation and outcome`() {
        service.install(TestExtensions.lib16)
        val en = runBlocking { service.sources.listSources(listSourcesRequest { lang = "en" }) }.sourcesList.single().id
        runBlocking { service.sources.popular(popularRequest { sourceId = en }) }
        site.override = { MockResponse.Builder().code(503).build() }
        assertFailsWith<io.grpc.StatusException> { runBlocking { service.sources.popular(popularRequest { sourceId = en }) } }

        val text = String(service.get("/metrics").body())
        assertTrue(
            Regex("""mihon_source_call_seconds_count\{operation="popular",outcome="ok",source="$en"} 1""").containsMatchIn(text),
            text,
        )
        assertTrue(
            Regex("""mihon_source_call_seconds_count\{operation="popular",outcome="SOURCE_DOWN",source="$en"} 1""").containsMatchIn(text),
        )
        assertTrue("""mihon_extensions{status="loaded"} 1.0""" in text)
        assertTrue("""mihon_sources 2.0""" in text)
        // No URLs or credentials in labels.
        assertTrue(site.baseUrl !in text)
    }

    @Test
    fun `a separate metrics port serves metrics without credentials`() {
        val port = service.app.metricsPort()!!
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, response.statusCode())
        assertTrue("jvm_memory" in response.body())
    }
}
