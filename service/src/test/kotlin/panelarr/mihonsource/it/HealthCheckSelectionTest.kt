// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.JobRun
import panelarr.mihon.v1.JobRunStatus
import panelarr.mihon.v1.MihonJobsServiceGrpcKt.MihonJobsServiceCoroutineStub
import panelarr.mihon.v1.getJobRunRequest
import panelarr.mihon.v1.listJobsRequest
import panelarr.mihon.v1.runJobRequest
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.mihonsource.fixtures.TestSigningKey
import panelarr.source.v1.listSourcesRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which sources a health check round covers: languages, a rotating sample, or given IDs. */
class HealthCheckSelectionTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var site: FakeSite
    private var service: TestService? = null
    private val jobs: MihonJobsServiceCoroutineStub get() = service!!.stub()

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

    /** Three sources: lib16 "en" and "fr", and lib14 "en". */
    private fun service(
        langs: Set<String> = emptySet(),
        sample: Int? = null,
    ): TestService =
        TestService(dir.resolve("data"), healthCheckLangs = langs, healthCheckSample = sample).also {
            service = it
            it.install(TestExtensions.lib16)
            it.install(TestExtensions.lib14Apk(TestSigningKey(), dir.resolve("lib14.apk")))
        }

    private fun langOf(): Map<String, String> =
        runBlocking { service!!.sources.listSources(listSourcesRequest {}) }.sourcesList.associate { it.id to it.lang }

    private fun run(params: Map<String, String> = emptyMap()): JobRun =
        runBlocking {
            val id =
                jobs
                    .runJob(
                        runJobRequest {
                            jobId = "source-health"
                            this.params.putAll(params)
                        },
                    ).run.id
            var run = jobs.getJobRun(getJobRunRequest { runId = id }).run
            while (run.summary.status == JobRunStatus.JOB_RUN_STATUS_RUNNING) {
                delay(100)
                run = jobs.getJobRun(getJobRunRequest { runId = id }).run
            }
            run
        }

    /** Source IDs a run checked, from the health timestamps it left. */
    private fun checkedSince(before: Map<String, String>): Set<String> {
        val now = runBlocking { service!!.sources.listSources(listSourcesRequest {}) }.sourcesList
        return now.filter { it.health.hasCheckedAt() && it.health.checkedAt.toString() != before[it.id] }.map { it.id }.toSet()
    }

    private fun checkTimes(): Map<String, String> =
        runBlocking {
            service!!.sources.listSources(
                listSourcesRequest {},
            )
        }.sourcesList.associate { it.id to it.health.checkedAt.toString() }

    @Test
    fun `the configured languages and sample are the defaults and the schedule's selection`() {
        service(langs = setOf("en"), sample = 2)
        val job = runBlocking { jobs.listJobs(listJobsRequest {}) }.jobsList.single { it.id == "source-health" }
        val params = job.paramsList.associateBy { it.name }
        assertEquals("en", params.getValue("langs").default)
        assertEquals(listOf("all", "en", "fr"), params.getValue("langs").choicesList)
        assertEquals("2", params.getValue("sample").default)
        assertEquals("int", params.getValue("sample").type)

        // Without params, a run (like a scheduled one) uses those defaults.
        val run = run()
        assertEquals(mapOf("langs" to "en", "sample" to "2", "steps" to "popular"), run.paramsMap)
        assertEquals(2L, run.summary.countsMap["sources"])
        assertEquals(2L, run.summary.countsMap["eligible"])
    }

    @Test
    fun `languages limit the round`() {
        service()
        val langs = langOf()
        val before = checkTimes()
        val run = run(mapOf("langs" to "fr"))
        assertEquals(1L, run.summary.countsMap["sources"])
        assertEquals(setOf("fr"), checkedSince(before).map { langs.getValue(it) }.toSet())

        // "all" covers every language.
        assertEquals(3L, run(mapOf("langs" to "all")).summary.countsMap["sources"])
    }

    @Test
    fun `a sample rotates through every eligible source`() {
        service(sample = 1)
        val seen = mutableSetOf<String>()
        repeat(3) {
            val before = checkTimes()
            val run = run()
            assertEquals(1L, run.summary.countsMap["sources"])
            assertEquals(3L, run.summary.countsMap["eligible"])
            assertTrue("(1 of 3 eligible)" in run.summary.message, run.summary.message)
            seen += checkedSince(before)
        }
        // Least recently checked first: three rounds of one covered all three.
        assertEquals(langOf().keys, seen)

        // A sample from one language rotates within it.
        val enSeen = mutableSetOf<String>()
        repeat(2) {
            val before = checkTimes()
            run(mapOf("langs" to "en"))
            enSeen += checkedSince(before)
        }
        assertEquals(langOf().filterValues { it == "en" }.keys, enSeen)
    }

    @Test
    fun `given source IDs override languages and sample`() {
        service(langs = setOf("en"), sample = 1)
        val fr = langOf().entries.single { it.value == "fr" }.key
        val run = run(mapOf("source_ids" to fr))
        assertEquals(mapOf("source_ids" to fr, "steps" to "popular"), run.paramsMap)
        assertEquals(1L, run.summary.countsMap["sources"])
        assertEquals("1 source OK", run.summary.message)
    }

    @Test
    fun `bad sample values are refused`() {
        service()
        assertEquals(400, service!!.send("POST", "/v1/admin/jobs/source-health:run", """{"params": {"sample": "-1"}}""").statusCode())
        assertEquals(400, service!!.send("POST", "/v1/admin/jobs/source-health:run", """{"params": {"sample": "many"}}""").statusCode())
        assertEquals(400, service!!.send("POST", "/v1/admin/jobs/source-health:run", """{"params": {"langs": "xx"}}""").statusCode())
    }
}
