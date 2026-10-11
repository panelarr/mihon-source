// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.jobs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihonsource.metrics.Metrics
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobRunnerTest {
    @TempDir
    lateinit var dir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runners = mutableListOf<JobRunner>()

    @AfterEach
    fun stop() {
        runners.forEach { it.stop() }
        scope.cancel()
    }

    /** A job that runs until [gate] completes, then does what [body] says. */
    private class GatedJob(
        override val id: String = "gated",
        override val schedule: JobSchedule = JobSchedule(null, "TEST_INTERVAL"),
        override val cancellable: Boolean = true,
        val body: (JobRunContext) -> Unit = {},
    ) : BackgroundJob {
        override val name = "Gated"
        override val description = "Waits for the test"
        override val group = "Test"
        override val params =
            listOf(
                JobParamSpec("force", JobParamSpec.Type.BOOL, "Force"),
                JobParamSpec("kinds", JobParamSpec.Type.STRING_LIST, "Kinds", default = "a", choices = listOf("a", "b")),
            )
        var gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()

        override suspend fun run(run: JobRunContext) {
            started.complete(Unit)
            run.progress("Working", 1, 2, "things")
            run.note(JobNote.Level.INFO, "started", "ref-1")
            gate.await()
            run.increment("things", 2)
            body(run)
        }
    }

    private fun runner(
        vararg jobs: BackgroundJob,
        keep: Int = 100,
        metrics: Metrics? = null,
    ) = JobRunner(dir, jobs.toList(), scope, keep = keep, metrics = metrics).also { runners += it }

    private fun JobRunner.await(runId: String): RunView =
        runBlocking {
            withTimeout(5_000) {
                while (run(runId).record.status == RunStatus.RUNNING) kotlinx.coroutines.delay(20)
            }
            run(runId)
        }

    @Test
    fun `a run reports progress while running and its outcome after`() {
        val job = GatedJob { it.message = "did 2 things" }
        val runner = runner(job)
        val started = runner.start("gated", RunTrigger.MANUAL, mapOf("force" to "true"), "admin")
        runBlocking { job.started.await() }

        val live = runner.run(started.id)
        assertEquals(RunStatus.RUNNING, live.record.status)
        assertEquals(JobProgressInfo("Working", 1, 2, "things"), live.progress)
        assertEquals(
            "ref-1",
            live.record.notes
                .single()
                .ref,
        )
        assertEquals(started.id, runner.current("gated")?.record?.id)
        // The default fills in params that weren't given.
        assertEquals(mapOf("force" to "true", "kinds" to "a"), live.record.params)

        job.gate.complete(Unit)
        val done = runner.await(started.id)
        assertEquals(RunStatus.SUCCEEDED, done.record.status)
        assertEquals("did 2 things", done.record.message)
        assertEquals(mapOf("things" to 2L), done.record.counts)
        assertEquals("admin", done.record.triggeredBy)
        assertNull(done.progress)
        assertEquals(started.id, runner.lastFinished("gated")?.id)
    }

    @Test
    fun `one run at a time`() {
        val job = GatedJob()
        val runner = runner(job)
        val first = runner.start("gated", RunTrigger.MANUAL)
        val e = assertFailsWith<JobAlreadyRunningException> { runner.start("gated", RunTrigger.MANUAL) }
        assertEquals(first.id, e.runId)
        job.gate.complete(Unit)
        runner.await(first.id)
        job.gate = CompletableDeferred(Unit)
        runner.await(runner.start("gated", RunTrigger.MANUAL).id)
    }

    @Test
    fun `failures, skips and exceptions end the run accordingly`() {
        val outcomes =
            listOf<(JobRunContext) -> Unit>(
                { it.fail("1 of 2 failed") },
                { it.skip("nothing to do") },
                { error("boom") },
            )
        val expected = listOf(RunStatus.FAILED to "1 of 2 failed", RunStatus.SKIPPED to "nothing to do", RunStatus.FAILED to "boom")
        outcomes.zip(expected).forEachIndexed { index, (body, outcome) ->
            val job = GatedJob(id = "job-$index", body = body).apply { gate.complete(Unit) }
            val runner = runner(job)
            val run = runner.await(runner.start(job.id, RunTrigger.MANUAL).id)
            assertEquals(outcome, run.record.status to run.record.message)
        }
    }

    @Test
    fun `cancelling stops a run, unless the job can't be cancelled`() {
        val job = GatedJob()
        val fixed = GatedJob(id = "fixed", cancellable = false)
        val runner = runner(job, fixed)
        val run = runner.start("gated", RunTrigger.MANUAL)
        runBlocking { job.started.await() }
        val cancelled = runBlocking { runner.cancel(run.id) }
        assertEquals(RunStatus.CANCELLED, cancelled.record.status)
        assertTrue(runner.current("gated") == null)

        // Not running any more.
        assertFailsWith<IllegalStateException> { runBlocking { runner.cancel(run.id) } }
        val other = runner.start("fixed", RunTrigger.MANUAL)
        assertFailsWith<IllegalStateException> { runBlocking { runner.cancel(other.id) } }
        assertFailsWith<NoSuchElementException> { runBlocking { runner.cancel("nope") } }
    }

    @Test
    fun `params are checked against the job`() {
        val runner = runner(GatedJob())
        assertFailsWith<IllegalArgumentException> { runner.start("gated", RunTrigger.MANUAL, mapOf("nope" to "1")) }
        assertFailsWith<IllegalArgumentException> { runner.start("gated", RunTrigger.MANUAL, mapOf("force" to "yes")) }
        assertFailsWith<IllegalArgumentException> { runner.start("gated", RunTrigger.MANUAL, mapOf("kinds" to "a,c")) }
        assertFailsWith<NoSuchElementException> { runner.start("missing", RunTrigger.MANUAL) }
        assertFailsWith<IllegalArgumentException> { JobRunner(dir, listOf(GatedJob(id = "sync:a")), scope) }
    }

    @Test
    fun `history is kept, trimmed and paged, and survives a restart`() {
        val job = GatedJob().apply { gate.complete(Unit) }
        val runner = runner(job, keep = 3)
        val ids = (1..5).map { runner.await(runner.start("gated", RunTrigger.MANUAL).id).record.id }
        val (all, more) = runner.runs("gated")
        assertEquals(ids.takeLast(3).reversed(), all.map { it.record.id })
        assertEquals(false, more)
        val (page, hasMore) = runner.runs("gated", offset = 0, limit = 2)
        assertEquals(2, page.size)
        assertTrue(hasMore)
        assertEquals(emptyList(), runner.runs("gated", RunStatus.FAILED).first)

        val again = runner(GatedJob(), keep = 3)
        assertEquals(ids.takeLast(3).reversed(), again.runs("gated").first.map { it.record.id })
    }

    @Test
    fun `a run cut short by a stop is marked interrupted`() {
        val job = GatedJob()
        val runner = runner(job)
        val run = runner.start("gated", RunTrigger.MANUAL)
        runBlocking { job.started.await() }
        runner.stop()
        assertEquals(RunStatus.FAILED to JobHistory.INTERRUPTED, runner.run(run.id).record.let { it.status to it.message })

        // A crash leaves the run marked running on disk; loading marks it interrupted.
        val file = dir.resolve("gated.json")
        Files.writeString(file, Files.readString(file).replace("\"FAILED\"", "\"RUNNING\""))
        val restarted = runner(GatedJob())
        assertEquals(RunStatus.FAILED, restarted.run(run.id).record.status)
        assertEquals(JobHistory.INTERRUPTED, restarted.run(run.id).record.message)
        assertNotNull(restarted.run(run.id).record.finishedAt)
    }

    @Test
    fun `schedules run jobs, skip while running, and count in metrics`() {
        val metrics = Metrics()
        val job = GatedJob(schedule = JobSchedule(Duration.ofMillis(200), "TEST_INTERVAL"))
        val runner = runner(job, metrics = metrics)
        runner.startSchedules()
        runBlocking { withTimeout(5_000) { job.started.await() } }
        assertTrue(runner.current("gated")!!.record.trigger == RunTrigger.SCHEDULE)
        // While the first run waits, the next scheduled ones are skipped.
        runBlocking {
            withTimeout(5_000) {
                while (runner.runs("gated", RunStatus.SKIPPED).first.isEmpty()) kotlinx.coroutines.delay(20)
            }
        }
        assertNotNull(runner.nextRunAt("gated"))
        job.gate.complete(Unit)
        runner.await(
            runner
                .runs("gated")
                .first
                .last()
                .record.id,
        )

        val scrape = metrics.scrape()
        assertTrue(
            Regex("""mihon_job_runs_total\{job="gated",status="skipped",trigger="schedule"} [1-9]""").containsMatchIn(scrape),
            scrape,
        )
        assertTrue(
            Regex("""mihon_job_runs_total\{job="gated",status="succeeded",trigger="schedule"} [1-9]""").containsMatchIn(scrape),
            scrape,
        )
        assertTrue("mihon_job_duration_seconds_count{job=\"gated\"}" in scrape, scrape)
        assertTrue("mihon_job_running{job=\"gated\"}" in scrape, scrape)
    }
}
