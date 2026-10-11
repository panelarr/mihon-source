// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.JobRunStatus
import panelarr.mihon.v1.MihonClientsServiceGrpcKt.MihonClientsServiceCoroutineStub
import panelarr.mihon.v1.MihonJobsServiceGrpcKt.MihonJobsServiceCoroutineStub
import panelarr.mihon.v1.createClientRequest
import panelarr.mihon.v1.getJobRunRequest
import panelarr.mihon.v1.listJobRunsRequest
import panelarr.mihon.v1.listJobsRequest
import panelarr.mihon.v1.runJobRequest
import panelarr.mihonsource.auth.Scopes
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.source.v1.SourceHealthStatus
import panelarr.source.v1.listSourcesRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The jobs API: listing, running, history, conflicts, cancelling and access. */
class JobsTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var site: FakeSite
    private lateinit var service: TestService
    private val jobs: MihonJobsServiceCoroutineStub get() = service.stub()

    @BeforeEach
    fun start() {
        site = FakeSite()
        System.setProperty("panelarr.testext.baseUrl", site.baseUrl)
        service = TestService(dir.resolve("data"), storeRefreshInterval = Duration.ofHours(6))
        service.install(TestExtensions.lib16)
    }

    @AfterEach
    fun stop() {
        service.close()
        site.close()
        System.clearProperty("panelarr.testext.baseUrl")
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.content

    /** Polls the run over HTTP until it has finished. */
    private fun finished(runId: String): JsonObject {
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            val run = json(String(service.get("/v1/admin/job-runs/$runId").body()))["run"]!!.jsonObject
            val summary = run["summary"]!!.jsonObject
            if (summary.string("status") != "JOB_RUN_STATUS_RUNNING") return run
            check(System.currentTimeMillis() < deadline) { "run $runId didn't finish" }
            Thread.sleep(100)
        }
    }

    @Test
    fun `jobs are listed with their schedules and params`() {
        val list = runBlocking { jobs.listJobs(listJobsRequest {}) }.jobsList.associateBy { it.id }
        assertEquals(setOf("store-refresh", "source-health"), list.keys)

        val refresh = list.getValue("store-refresh")
        assertTrue(refresh.schedule.enabled)
        assertEquals(Duration.ofHours(6).seconds, refresh.schedule.interval.seconds)
        assertEquals("MIHON_STORE_REFRESH_INTERVAL", refresh.schedule.source)
        assertTrue(refresh.hasNextRunAt())
        assertEquals(listOf("store_id"), refresh.paramsList.map { it.name })

        val health = list.getValue("source-health")
        assertEquals(false, health.schedule.enabled)
        assertEquals(false, health.schedule.hasInterval())
        assertEquals(false, health.hasNextRunAt())
        val steps = health.paramsList.single { it.name == "steps" }
        assertEquals("string_list", steps.type)
        assertEquals("popular", steps.default)
        assertTrue("image" in steps.choicesList)

        val http = json(String(service.get("/v1/admin/jobs/source-health").body()))
        assertEquals("Check sources", http["job"]!!.jsonObject.string("name"))
    }

    @Test
    fun `a manual run over HTTP records health, counts and history`() {
        val response = service.send("POST", "/v1/admin/jobs/source-health:run", """{"params": {"steps": "popular,latest"}}""")
        assertEquals(200, response.statusCode(), response.body())
        val runId = json(response.body())["run"]!!.jsonObject.string("id")!!

        val run = finished(runId)
        val summary = run["summary"]!!.jsonObject
        assertEquals("JOB_RUN_STATUS_SUCCEEDED", summary.string("status"), run.toString())
        assertEquals("JOB_TRIGGER_MANUAL", summary.string("trigger"))
        assertEquals("admin", summary.string("triggeredBy"))
        assertEquals("2 sources OK", summary.string("message"))
        assertEquals("2", summary["counts"]!!.jsonObject.string("ok"))
        assertEquals("popular,latest", run["params"]!!.jsonObject.string("steps"))
        assertTrue(site.requestsTo("/api/latest").size == 2)

        val health = runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesList.map { it.health.status }
        assertTrue(health.all { it == SourceHealthStatus.SOURCE_HEALTH_STATUS_OK })

        val job = runBlocking { jobs.listJobs(listJobsRequest {}) }.jobsList.single { it.id == "source-health" }
        assertEquals(runId, job.lastRun.id)
        val runs = json(String(service.get("/v1/admin/jobs/source-health/runs?pageSize=10&status=JOB_RUN_STATUS_SUCCEEDED").body()))
        assertEquals(
            runId,
            runs["runs"]!!
                .jsonArray
                .single()
                .jsonObject
                .string("id"),
        )

        val metrics = String(service.get("/metrics").body())
        assertTrue(
            Regex("""mihon_job_runs_total\{job="source-health",status="succeeded",trigger="manual"} 1""").containsMatchIn(metrics),
            metrics,
        )
    }

    @Test
    fun `failing sources are notes, and a store refresh with no stores is skipped`() {
        site.override = { request ->
            if (request.url.encodedPath == "/api/popular") {
                mockwebserver3.MockResponse
                    .Builder()
                    .code(503)
                    .build()
            } else {
                null
            }
        }
        val health = runBlocking { jobs.runJob(runJobRequest { jobId = "source-health" }) }.run
        val run = runBlocking { awaitRun(health.id) }
        assertEquals(JobRunStatus.JOB_RUN_STATUS_SUCCEEDED, run.summary.status)
        assertEquals("0 of 2 sources OK", run.summary.message)
        assertEquals(2, run.notesList.size)
        assertTrue(run.notesList.all { it.level == "warn" && "SOURCE_DOWN" in it.message && it.ref.isNotEmpty() })

        val refresh = runBlocking { jobs.runJob(runJobRequest { jobId = "store-refresh" }) }.run
        val skipped = runBlocking { awaitRun(refresh.id) }
        assertEquals(JobRunStatus.JOB_RUN_STATUS_SKIPPED, skipped.summary.status)
        assertEquals("No stores to refresh", skipped.summary.message)
    }

    private suspend fun awaitRun(id: String): panelarr.mihon.v1.JobRun {
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            val run = jobs.getJobRun(getJobRunRequest { runId = id }).run
            if (run.summary.status != JobRunStatus.JOB_RUN_STATUS_RUNNING) return run
            check(System.currentTimeMillis() < deadline) { "run $id didn't finish" }
            kotlinx.coroutines.delay(100)
        }
    }

    @Test
    fun `a second run is refused while one runs, and a run can be cancelled`() {
        site.override = { request ->
            if (request.url.encodedPath == "/api/popular") Thread.sleep(3_000)
            null
        }
        // An empty body works like {}.
        val first = service.send("POST", "/v1/admin/jobs/source-health:run", "")
        assertEquals(200, first.statusCode(), first.body())
        val runId = json(first.body())["run"]!!.jsonObject.string("id")!!

        val second = service.send("POST", "/v1/admin/jobs/source-health:run", "{}")
        assertEquals(409, second.statusCode(), second.body())
        val problem = json(second.body())
        assertEquals("ALREADY_RUNNING", problem.string("reason"))
        assertEquals(runId, problem.string("currentRunId"))

        val live = json(String(service.get("/v1/admin/job-runs/$runId").body()))["run"]!!.jsonObject
        assertEquals("sources", live["progress"]!!.jsonObject.string("unit"))

        val cancelled = service.send("POST", "/v1/admin/job-runs/$runId:cancel", "")
        assertEquals(200, cancelled.statusCode(), cancelled.body())
        assertEquals("JOB_RUN_STATUS_CANCELLED", json(cancelled.body())["run"]!!.jsonObject.string("status"))
        // A cancelled check isn't recorded as the source failing.
        val health = runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesList.map { it.health.status }
        assertTrue(health.none { it == SourceHealthStatus.SOURCE_HEALTH_STATUS_FAILING }, health.toString())

        val again = service.send("POST", "/v1/admin/job-runs/$runId:cancel", "{}")
        assertEquals(400, again.statusCode(), again.body())
    }

    @Test
    fun `bad requests and access`() {
        assertEquals(404, service.send("POST", "/v1/admin/jobs/nope:run", "{}").statusCode())
        assertEquals(404, service.get("/v1/admin/job-runs/nope").statusCode())
        assertEquals(400, service.send("POST", "/v1/admin/jobs/source-health:run", """{"params": {"steps": "dance"}}""").statusCode())
        assertEquals(404, service.send("POST", "/v1/admin/jobs/source-health:run", """{"params": {"source_ids": "42"}}""").statusCode())
        assertEquals(404, service.send("POST", "/v1/admin/jobs/store-refresh:run", """{"params": {"store_id": "nope"}}""").statusCode())
        assertEquals(400, service.get("/v1/admin/jobs/source-health/runs?pageToken=x").statusCode())

        val reader =
            runBlocking {
                service
                    .stub<MihonClientsServiceCoroutineStub>()
                    .createClient(
                        createClientRequest {
                            name = "reader"
                            scopes += Scopes.SOURCES_READ
                        },
                    ).apiKey
            }
        assertEquals(403, service.get("/v1/admin/jobs", reader).statusCode())
        assertEquals(403, service.send("POST", "/v1/admin/jobs/source-health:run", "{}", reader).statusCode())
        assertEquals(401, service.get("/v1/admin/jobs", null).statusCode())
        assertEquals(0, runBlocking { jobs.listJobRuns(listJobRunsRequest { jobId = "source-health" }) }.runsCount)
    }
}
