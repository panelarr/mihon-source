// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import com.google.protobuf.Duration
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.Job
import panelarr.mihon.v1.JobRun
import panelarr.mihon.v1.JobRunStatus
import panelarr.mihon.v1.MihonJobsServiceGrpcKt.MihonJobsServiceCoroutineStub
import panelarr.mihon.v1.getHealthCheckScheduleRequest
import panelarr.mihon.v1.getJobRequest
import panelarr.mihon.v1.getJobRunRequest
import panelarr.mihon.v1.healthCheckSchedule
import panelarr.mihon.v1.runJobRequest
import panelarr.mihon.v1.setHealthCheckScheduleRequest
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.source.v1.listSourcesRequest
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The health-check schedule set over the admin API, and what a run for given sources notes. */
class HealthCheckScheduleTest {
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

    private fun launch(): TestService {
        service?.close()
        return TestService(
            dir.resolve("data"),
            healthCheckInterval = java.time.Duration.ofHours(6),
            healthCheckLangs = setOf("en"),
            healthCheckSample = 10,
        ).also { service = it }
    }

    private fun job(): Job =
        runBlocking {
            service!!
                .stub<MihonJobsServiceCoroutineStub>()
                .getJob(getJobRequest { jobId = "source-health" })
                .job
        }

    private fun hours(n: Long): Duration = Duration.newBuilder().setSeconds(n * 3600).build()

    @Test
    fun `the schedule comes from the environment until it's set over the API, and survives restarts`() {
        launch()
        val env = runBlocking { service!!.admin.getHealthCheckSchedule(getHealthCheckScheduleRequest {}) }.schedule
        assertEquals(hours(6), env.interval)
        assertEquals(listOf("en"), env.langsList)
        assertEquals(10, env.sample)
        assertTrue(env.source.startsWith("MIHON_HEALTH_CHECK_INTERVAL"))
        assertEquals("MIHON_HEALTH_CHECK_INTERVAL", job().schedule.source)

        val set =
            runBlocking {
                service!!.admin.setHealthCheckSchedule(
                    setHealthCheckScheduleRequest {
                        schedule =
                            healthCheckSchedule {
                                interval = hours(1)
                                langs += listOf("fr", " ")
                                sample = 2
                            }
                    },
                )
            }.schedule
        assertEquals("SetHealthCheckSchedule", set.source)
        assertEquals(listOf("fr"), set.langsList)
        var job = job()
        assertEquals(hours(1), job.schedule.interval)
        assertEquals("SetHealthCheckSchedule", job.schedule.source)
        val params = job.paramsList.associateBy { it.name }
        assertEquals("fr", params.getValue("langs").default)
        assertEquals("2", params.getValue("sample").default)
        // The next run follows the new interval (plus up to 10% delay) at once.
        val next = Instant.ofEpochSecond(job.nextRunAt.seconds)
        assertTrue(next.isBefore(Instant.now().plusSeconds(3600 * 11 / 10 + 5)), "next run $next")

        launch()
        assertEquals(hours(1), job().schedule.interval)

        // No interval: no scheduled checks.
        val noInterval = setHealthCheckScheduleRequest { schedule = healthCheckSchedule { sample = 3 } }
        runBlocking { service!!.admin.setHealthCheckSchedule(noInterval) }
        job = job()
        assertFalse(job.schedule.enabled)
        assertFalse(job.hasNextRunAt())

        // Unset goes back to the environment's.
        val back = runBlocking { service!!.admin.setHealthCheckSchedule(setHealthCheckScheduleRequest {}) }.schedule
        assertEquals(hours(6), back.interval)
        assertEquals(hours(6), job().schedule.interval)
        assertTrue(job().hasNextRunAt())
    }

    @Test
    fun `too short an interval is refused`() {
        launch()
        val tenSeconds = Duration.newBuilder().setSeconds(10).build()
        val request = setHealthCheckScheduleRequest { schedule = healthCheckSchedule { interval = tenSeconds } }
        val error = runCatching { runBlocking { service!!.admin.setHealthCheckSchedule(request) } }.exceptionOrNull()
        assertEquals(Status.Code.INVALID_ARGUMENT, (error as StatusException).status.code)
    }

    @Test
    fun `a run for given sources notes each step`() {
        launch().install(TestExtensions.lib16)
        val source = runBlocking { service!!.sources.listSources(listSourcesRequest {}) }.sourcesList.first()
        val jobs = service!!.stub<MihonJobsServiceCoroutineStub>()
        val run: JobRun =
            runBlocking {
                val id =
                    jobs
                        .runJob(
                            runJobRequest {
                                jobId = "source-health"
                                params.putAll(mapOf("source_ids" to source.id, "steps" to "details"))
                            },
                        ).run.id
                var run = jobs.getJobRun(getJobRunRequest { runId = id }).run
                while (run.summary.status == JobRunStatus.JOB_RUN_STATUS_RUNNING) {
                    delay(100)
                    run = jobs.getJobRun(getJobRunRequest { runId = id }).run
                }
                run
            }
        val notes = run.notesList.map { it.message }
        assertTrue(notes.any { it.contains("popular: ok") }, "notes: $notes")
        assertTrue(notes.any { it.contains("details: ok") }, "notes: $notes")
        assertTrue(run.notesList.all { it.ref == source.id })
    }
}
