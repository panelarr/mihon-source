// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import com.google.protobuf.Struct
import com.google.protobuf.Value
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.ProfileOrigin
import panelarr.mihon.v1.SolverType
import panelarr.mihon.v1.assignProfileRequest
import panelarr.mihon.v1.listAssignmentsRequest
import panelarr.mihon.v1.listProfilesRequest
import panelarr.mihon.v1.networkProfile
import panelarr.mihon.v1.proxy
import panelarr.mihon.v1.putProfileRequest
import panelarr.mihon.v1.putSolverRequest
import panelarr.mihon.v1.solver
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.source.v1.listSourcesRequest
import panelarr.source.v1.popularRequest
import panelarr.source.v1.setNetworkProfileRequest
import panelarr.source.v1.setSettingsRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import panelarr.source.v1.Proxy as PushedProxy
import panelarr.source.v1.networkProfile as pushedProfile

/** Per-source network profiles: proxies, pushed overrides, proxy credentials and solvers. */
class NetworkProfilesTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var service: TestService
    private lateinit var proxyA: FakeSite
    private lateinit var proxyB: FakeSite
    private lateinit var en: String
    private lateinit var fr: String

    @BeforeEach
    fun start() {
        // Each "proxy" is a fake site: plain-HTTP proxying sends it the full target URL.
        proxyA = FakeSite()
        proxyB = FakeSite()
        service = TestService(dir.resolve("data"))
        service.install(TestExtensions.lib16)
        val sources = runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesList
        en = sources.single { it.lang == "en" }.id
        fr = sources.single { it.lang == "fr" }.id
        // A host name that only the proxies "resolve".
        listOf(en, fr).forEach { id ->
            runBlocking {
                service.sources.setSettings(
                    setSettingsRequest {
                        sourceId = id
                        values =
                            Struct
                                .newBuilder()
                                .putFields(
                                    "base_url",
                                    Value.newBuilder().setStringValue("http://catalog.test").build(),
                                ).build()
                    },
                )
            }
        }
    }

    @AfterEach
    fun stop() {
        service.close()
        proxyA.close()
        proxyB.close()
    }

    private fun putProfile(
        id: String,
        proxyUrl: String,
        username: String = "",
        password: String = "",
        solverId: String = "",
    ) = runBlocking {
        service.integrations.putProfile(
            putProfileRequest {
                profile =
                    networkProfile {
                        this.id = id
                        name = "Profile $id"
                        proxy =
                            proxy {
                                url = proxyUrl
                                this.username = username
                                this.password = password
                            }
                        this.solverId = solverId
                    }
            },
        )
    }

    private fun assign(
        sourceId: String,
        profileId: String,
    ) = runBlocking {
        service.integrations.assignProfile(
            assignProfileRequest {
                this.sourceId = sourceId
                this.profileId = profileId
            },
        )
    }

    private fun popular(sourceId: String) = runBlocking { service.sources.popular(popularRequest { this.sourceId = sourceId }) }

    @Test
    fun `two sources use different proxies`() {
        putProfile("a", proxyA.baseUrl)
        putProfile("b", proxyB.baseUrl)
        assign(en, "a")
        assign(fr, "b")

        popular(en)
        assertEquals(1, proxyA.requests.size)
        assertEquals(
            "catalog.test",
            proxyA.requests
                .single()
                .url.host,
        )
        assertEquals(0, proxyB.requests.size)

        popular(fr)
        assertEquals(1, proxyB.requests.size)
        assertEquals(
            "fr",
            proxyB.requests
                .single()
                .url
                .queryParameter("lang"),
        )
        assertEquals(1, proxyA.requests.size)

        // No JVM-wide proxy settings were touched.
        listOf("http.proxyHost", "https.proxyHost", "socksProxyHost").forEach { assertNull(System.getProperty(it), it) }

        val assignments = runBlocking { service.integrations.listAssignments(listAssignmentsRequest {}) }.assignmentsList
        assertEquals(ProfileOrigin.PROFILE_ORIGIN_ASSIGNED, assignments.single { it.sourceId == en }.effective)
    }

    @Test
    fun `a pushed profile overrides the local assignment`() {
        putProfile("a", proxyA.baseUrl)
        assign(en, "a")
        runBlocking {
            service.sources.setNetworkProfile(
                setNetworkProfileRequest {
                    sourceId = en
                    profile =
                        pushedProfile {
                            name = "from-host"
                            proxy = PushedProxy.newBuilder().setUrl(proxyB.baseUrl).build()
                        }
                },
            )
        }
        popular(en)
        assertEquals(0, proxyA.requests.size)
        assertEquals(1, proxyB.requests.size)
        val assignment =
            runBlocking { service.integrations.listAssignments(listAssignmentsRequest {}) }.assignmentsList.single {
                it.sourceId ==
                    en
            }
        assertEquals(ProfileOrigin.PROFILE_ORIGIN_PUSHED, assignment.effective)
        assertEquals("from-host", assignment.pushedProfileName)

        // Removing the pushed profile falls back to the assignment.
        runBlocking { service.sources.setNetworkProfile(setNetworkProfileRequest { sourceId = en }) }
        popular(en)
        assertEquals(1, proxyA.requests.size)
    }

    @Test
    fun `proxy credentials are used, encrypted at rest and never returned`() {
        proxyA.override = { request ->
            if (request.headers["Proxy-Authorization"] == null) {
                MockResponse
                    .Builder()
                    .code(407)
                    .addHeader("Proxy-Authenticate", "Basic realm=\"test\"")
                    .build()
            } else {
                null
            }
        }
        putProfile("a", proxyA.baseUrl, username = "user", password = "s3cret-pass")
        assign(en, "a")
        popular(en)
        val authorized = proxyA.requests.last()
        assertEquals(okhttp3.Credentials.basic("user", "s3cret-pass"), authorized.headers["Proxy-Authorization"])

        val listed = runBlocking { service.integrations.listProfiles(listProfilesRequest {}) }.profilesList.single()
        assertTrue(listed.proxy.hasPassword)
        assertEquals("", listed.proxy.password)
        val stored = Files.readString(dir.resolve("data/config/integrations.json"))
        assertFalse("s3cret-pass" in stored)

        // Saving again with an empty password keeps the stored one.
        putProfile("a", proxyA.baseUrl, username = "user")
        popular(en)
        assertEquals(okhttp3.Credentials.basic("user", "s3cret-pass"), proxyA.requests.last().headers["Proxy-Authorization"])
    }

    @Test
    fun `challenges are solved once per host, concurrently`() {
        val solves = AtomicInteger()
        val solverServer = MockWebServer()
        solverServer.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    solves.incrementAndGet()
                    Thread.sleep(SOLVE_MILLIS)
                    val body = request.body?.utf8().orEmpty()
                    assertTrue("\"proxy\"" in body, "the solver must be told about the source's proxy")
                    return MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """{"status":"ok","message":"","solution":{"url":"http://catalog.test/","status":200,""" +
                                """"userAgent":"Solver UA","cookies":[{"name":"cf_clearance","value":"ok",""" +
                                """"domain":"catalog.test","path":"/"}],"response":"<html></html>"}}""",
                        ).build()
                }
            }
        solverServer.start()
        try {
            proxyA.override = { request ->
                val cleared = request.headers["Cookie"]?.contains("cf_clearance=ok") == true
                if (cleared) {
                    assertEquals("Solver UA", request.headers["User-Agent"])
                    null
                } else {
                    MockResponse
                        .Builder()
                        .code(503)
                        .addHeader("Server", "cloudflare")
                        .body("challenge")
                        .build()
                }
            }
            runBlocking {
                service.integrations.putSolver(
                    putSolverRequest {
                        solver =
                            solver {
                                id = "fs"
                                name = "Solver"
                                type = SolverType.SOLVER_TYPE_FLARESOLVERR
                                url = solverServer.url("/").toString()
                            }
                    },
                )
            }
            putProfile("a", proxyA.baseUrl, solverId = "fs")
            assign(en, "a")

            val pages =
                runBlocking {
                    (1..3)
                        .map { page ->
                            async {
                                service.sources.popular(
                                    popularRequest {
                                        sourceId = en
                                        this.page = page
                                    },
                                )
                            }
                        }.awaitAll()
                }
            assertEquals(3, pages.size)
            assertEquals(1, solves.get())
        } finally {
            solverServer.close()
        }
    }

    private companion object {
        const val SOLVE_MILLIS = 500L
    }
}
