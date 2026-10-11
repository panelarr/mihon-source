package panelarr.mihonsource

import com.linecorp.armeria.client.grpc.GrpcClients
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.MihonClientsServiceGrpcKt.MihonClientsServiceCoroutineStub
import panelarr.mihon.v1.PrincipalKind
import panelarr.mihon.v1.createClientRequest
import panelarr.mihon.v1.listClientsRequest
import panelarr.mihon.v1.whoAmIRequest
import panelarr.mihonsource.auth.Scopes
import panelarr.mihonsource.config.AppConfig
import panelarr.source.v1.SourcePluginServiceGrpcKt.SourcePluginServiceCoroutineStub
import panelarr.source.v1.getInfoRequest
import panelarr.source.v1.healthRequest
import panelarr.source.v1.listSourcesRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Starts the whole service and checks auth over gRPC and over HTTP/JSON. */
class ApiAuthIntegrationTest {
    @TempDir
    lateinit var dataDir: Path

    private lateinit var app: Application
    private lateinit var base: String
    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun start() {
        app = Application(AppConfig(dataDir = dataDir, host = "127.0.0.1", port = 0, adminToken = ADMIN))
        base = "http://127.0.0.1:${app.start()}"
    }

    @AfterEach
    fun stop() = app.stop()

    private inline fun <reified T : Any> stub(token: String?): T =
        GrpcClients
            .builder(base)
            .apply { if (token != null) addHeader("authorization", "Bearer $token") }
            .build(T::class.java)

    private fun get(
        path: String,
        token: String?,
    ): HttpResponse<String> = send(HttpRequest.newBuilder(URI.create(base + path)).GET(), token)

    private fun post(
        path: String,
        body: String,
        token: String?,
    ): HttpResponse<String> = send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(body)), token)

    private fun send(
        builder: HttpRequest.Builder,
        token: String?,
    ): HttpResponse<String> {
        if (token != null) builder.header("Authorization", "Bearer $token")
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun createReader(): String =
        runBlocking {
            stub<MihonClientsServiceCoroutineStub>(ADMIN)
                .createClient(
                    createClientRequest {
                        name = "reader"
                        scopes += Scopes.SOURCES_READ
                    },
                ).apiKey
        }

    @Test
    fun `health needs no credentials`() {
        runBlocking { stub<SourcePluginServiceCoroutineStub>(null).health(healthRequest {}) }
        assertEquals(200, get("/v1/health", null).statusCode())
    }

    @Test
    fun `other calls need a valid token`() {
        val error =
            assertFailsWith<StatusException> { runBlocking { stub<SourcePluginServiceCoroutineStub>(null).getInfo(getInfoRequest {}) } }
        assertEquals(Status.Code.UNAUTHENTICATED, error.status.code)
        assertEquals(401, get("/v1/info", null).statusCode())
        assertEquals(401, get("/v1/sources", "msk_wrong").statusCode())
    }

    @Test
    fun `a sources-read key can read sources but not call admin APIs`() {
        val key = createReader()

        runBlocking { stub<SourcePluginServiceCoroutineStub>(key).listSources(listSourcesRequest {}) }
        assertEquals(200, get("/v1/sources", key).statusCode())

        val error =
            assertFailsWith<StatusException> {
                runBlocking { stub<MihonClientsServiceCoroutineStub>(key).listClients(listClientsRequest {}) }
            }
        assertEquals(Status.Code.PERMISSION_DENIED, error.status.code)
        assertEquals(403, get("/v1/admin/clients", key).statusCode())
        assertEquals(403, post("/v1/admin/clients", """{"name":"evil","scopes":["admin"]}""", key).statusCode())
    }

    @Test
    fun `whoami describes the caller`() {
        val key = createReader()
        runBlocking {
            val admin = stub<MihonClientsServiceCoroutineStub>(ADMIN).whoAmI(whoAmIRequest {})
            assertEquals(PrincipalKind.PRINCIPAL_KIND_ADMIN, admin.kind)
            val client = stub<MihonClientsServiceCoroutineStub>(key).whoAmI(whoAmIRequest {})
            assertEquals(PrincipalKind.PRINCIPAL_KIND_CLIENT, client.kind)
            assertEquals(listOf(Scopes.SOURCES_READ), client.scopesList)
        }
    }

    @Test
    fun `json bodies work without a content type and keys are shown once`() {
        val created = post("/v1/admin/clients", """{"name":"script","scopes":["sources:read"]}""", ADMIN)
        assertEquals(200, created.statusCode(), created.body())
        assertTrue("\"apiKey\":\"msk_" in created.body())

        val listed = get("/v1/admin/clients", ADMIN)
        assertTrue("script" in listed.body())
        assertTrue("apiKey" !in listed.body())
    }

    @Test
    fun `openapi document and docs are served`() {
        val doc = get("/openapi.yaml", null)
        assertEquals(200, doc.statusCode())
        assertTrue("/v1/sources/{sourceId}/popular" in doc.body())
        assertEquals(200, get("/docs/", null).statusCode())
    }

    companion object {
        private const val ADMIN = "test-admin-token"
    }
}
