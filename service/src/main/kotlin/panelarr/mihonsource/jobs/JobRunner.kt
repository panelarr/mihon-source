// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import panelarr.mihonsource.metrics.Metrics
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** A run was asked for while another run of the job is going on. */
class JobAlreadyRunningException(
    val jobId: String,
    val runId: String,
) : IllegalStateException("job '$jobId' is already running (run $runId)")

/** A run as the API shows it: the stored record, plus live state while it runs. */
data class RunView(
    val record: RunRecord,
    val progress: JobProgressInfo? = null,
)

/**
 * Runs [BackgroundJob]s on their schedules and on request, one run per job at a time,
 * and keeps their history in `jobs/` (see [JobHistory]). A scheduled run that comes due
 * while the job is still running is recorded as skipped. Each scheduled run starts
 * after the interval plus up to 10% random delay, so many instances don't start
 * together.
 */
class JobRunner(
    dir: Path,
    jobs: List<BackgroundJob>,
    private val scope: CoroutineScope,
    keep: Int = DEFAULT_KEEP,
    private val metrics: Metrics? = null,
    private val random: Random = Random.Default,
    private val now: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(JobRunner::class.java)
    private val jobs: Map<String, BackgroundJob> = jobs.associateBy { it.id }
    private val histories: Map<String, JobHistory> = jobs.associate { it.id to JobHistory(dir.resolve("${it.id}.json"), keep, now) }
    private val active = ConcurrentHashMap<String, Active>()
    private val nextRuns = ConcurrentHashMap<String, Instant>()
    private val schedules = ArrayList<kotlinx.coroutines.Job>()
    private val lock = Any()

    @Volatile
    private var stopping = false

    private class Active(
        val record: RunRecord,
        val context: JobRunContext,
    ) {
        lateinit var task: kotlinx.coroutines.Job
    }

    init {
        jobs.forEach { job ->
            require(ID.matches(job.id)) { "job ID '${job.id}' must match $ID" }
            metrics?.jobRunning(job.id) { if (active.containsKey(job.id)) 1 else 0 }
        }
    }

    fun jobs(): List<BackgroundJob> = jobs.values.toList()

    fun job(id: String): BackgroundJob = jobs[id] ?: throw NoSuchElementException("job '$id' not found")

    /** The running run of [jobId], if any. */
    fun current(jobId: String): RunView? = active[jobId]?.let(::view)

    fun lastFinished(jobId: String): RunRecord? = histories.getValue(job(jobId).id).lastFinished()

    fun nextRunAt(jobId: String): Instant? = nextRuns[jobId]

    /** Runs of [jobId], newest first, optionally only those with [status]. */
    fun runs(
        jobId: String,
        status: RunStatus? = null,
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
    ): Pair<List<RunView>, Boolean> {
        val matching =
            histories
                .getValue(job(jobId).id)
                .all()
                .map { record -> active[jobId]?.takeIf { it.record.id == record.id }?.let(::view) ?: RunView(record) }
                .filter { status == null || it.record.status == status }
        return matching.drop(offset).take(limit) to (matching.size > offset + limit)
    }

    fun run(runId: String): RunView {
        active.values.firstOrNull { it.record.id == runId }?.let { return view(it) }
        return histories.values.firstNotNullOfOrNull { it.find(runId) }?.let(::RunView)
            ?: throw NoSuchElementException("job run '$runId' not found")
    }

    /**
     * Starts a run of [jobId] and returns it at once.
     *
     * @throws JobAlreadyRunningException when the job is running.
     */
    fun start(
        jobId: String,
        trigger: RunTrigger,
        params: Map<String, String> = emptyMap(),
        triggeredBy: String = "",
    ): RunRecord {
        val job = job(jobId)
        val checked = job.normalize(JobParams.check(job, params))
        job.validate(checked)
        synchronized(lock) {
            active[jobId]?.let { throw JobAlreadyRunningException(jobId, it.record.id) }
            val record =
                RunRecord(
                    id = newRunId(),
                    jobId = jobId,
                    status = RunStatus.RUNNING,
                    trigger = trigger,
                    startedAt = now().toString(),
                    triggeredBy = triggeredBy,
                    params = checked,
                )
            histories.getValue(jobId).add(record)
            val running = Active(record, JobRunContext(checked, now = now))
            running.task = scope.launch(start = CoroutineStart.LAZY) { execute(job, running) }
            active[jobId] = running
            running.task.start()
            return record
        }
    }

    /** Cancels a running run and waits briefly for it to stop. */
    suspend fun cancel(runId: String): RunView {
        val running = active.values.firstOrNull { it.record.id == runId }
        if (running == null) {
            run(runId) // Not found, or:
            throw IllegalStateException("job run '$runId' isn't running")
        }
        check(jobs.getValue(running.record.jobId).cancellable) { "job '${running.record.jobId}' can't be cancelled" }
        running.task.cancel()
        withTimeoutOrNull(CANCEL_WAIT.toMillis()) { running.task.join() }
        return run(runId)
    }

    /** Starts the schedules of the jobs that have one. */
    fun startSchedules() {
        for (job in jobs.values) {
            val interval = job.schedule.interval?.takeIf { job.schedule.enabled } ?: continue
            log.info("Running job {} every {}", job.id, interval)
            schedules +=
                scope.launch {
                    fun nextWait() = interval.toMillis() + random.nextLong(interval.toMillis() / JITTER_DIVISOR + 1)
                    var wait = nextWait()
                    nextRuns[job.id] = now().plusMillis(wait)
                    while (isActive) {
                        delay(wait)
                        // The next run is known before this one starts, so the job always
                        // shows a next run while its schedule is on.
                        wait = nextWait()
                        nextRuns[job.id] = now().plusMillis(wait)
                        scheduled(job)
                    }
                }
        }
    }

    /** Stops the schedules and running runs; those runs are recorded as interrupted. */
    fun stop() {
        stopping = true
        schedules.forEach { it.cancel() }
        val tasks = active.values.map { it.task }
        tasks.forEach { it.cancel() }
        runBlocking { withTimeoutOrNull(CANCEL_WAIT.toMillis()) { tasks.joinAll() } }
    }

    private fun scheduled(job: BackgroundJob) {
        try {
            start(job.id, RunTrigger.SCHEDULE)
        } catch (e: JobAlreadyRunningException) {
            val at = now().toString()
            val skipped =
                RunRecord(
                    id = newRunId(),
                    jobId = job.id,
                    status = RunStatus.SKIPPED,
                    trigger = RunTrigger.SCHEDULE,
                    startedAt = at,
                    finishedAt = at,
                    message = "The previous run (${e.runId}) was still running",
                )
            histories.getValue(job.id).add(skipped)
            metrics?.jobRun(job.id, skipped.status.label, skipped.trigger.label, Duration.ZERO)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn("Couldn't start scheduled job {}: {}", job.id, e.message)
        }
    }

    private suspend fun execute(
        job: BackgroundJob,
        running: Active,
    ) {
        val context = running.context
        val (status, message) =
            try {
                job.run(context)
                when (context.outcome) {
                    JobRunContext.Outcome.DONE -> RunStatus.SUCCEEDED to context.message
                    JobRunContext.Outcome.FAILED -> RunStatus.FAILED to context.message
                    JobRunContext.Outcome.SKIPPED -> RunStatus.SKIPPED to context.message
                }
            } catch (
                // A cancelled run is an outcome to record, not an error to pass on.
                @Suppress("SwallowedException") e: CancellationException,
            ) {
                if (stopping) RunStatus.FAILED to JobHistory.INTERRUPTED else RunStatus.CANCELLED to "Cancelled"
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                log.warn("Job {} failed: {}", job.id, e.message, e)
                RunStatus.FAILED to (e.message ?: e.javaClass.simpleName)
            }
        withContext(NonCancellable) {
            val finishedAt = now()
            val record =
                running.record.copy(
                    status = status,
                    finishedAt = finishedAt.toString(),
                    message = message,
                    counts = context.counts(),
                    notes = context.notes().map { NoteRecord(it.at.toString(), it.level, it.message, it.ref) },
                )
            histories.getValue(job.id).replace(record)
            val took = Duration.between(Instant.parse(running.record.startedAt), finishedAt)
            // Counted before the run stops showing as running, so whoever sees it
            // finished also sees it in the metrics.
            metrics?.jobRun(job.id, status.label, record.trigger.label, took)
            active.remove(job.id)
            log.info("Job {} {} in {} ms{}", job.id, status.label, took.toMillis(), if (message.isBlank()) "" else ": $message")
        }
    }

    private fun view(running: Active): RunView {
        val context = running.context
        val record =
            running.record.copy(
                message = context.message,
                counts = context.counts(),
                notes = context.notes().map { NoteRecord(it.at.toString(), it.level, it.message, it.ref) },
            )
        return RunView(record, context.progress)
    }

    /** Sortable: the start time in milliseconds, then random digits. */
    private fun newRunId(): String = "%012x%06x".format(now().toEpochMilli(), random.nextInt(RUN_ID_RANDOM))

    companion object {
        const val DEFAULT_KEEP = 100
        val ID = Regex("^[a-z0-9][a-z0-9._-]*$")
        private const val JITTER_DIVISOR = 10
        private const val RUN_ID_RANDOM = 0x1000000
        private val CANCEL_WAIT: Duration = Duration.ofSeconds(5)
    }
}

/** The metrics label: lower case. */
val RunStatus.label: String get() = name.lowercase()

val RunTrigger.label: String get() = name.lowercase()

/** Checks run params against a job's declared params and fills in defaults. */
internal object JobParams {
    fun check(
        job: BackgroundJob,
        params: Map<String, String>,
    ): Map<String, String> {
        val specs = job.params.associateBy { it.name }
        params.keys.firstOrNull { it !in specs }?.let { throw IllegalArgumentException("job '${job.id}' has no param '$it'") }
        val result = LinkedHashMap<String, String>()
        for (spec in job.params) {
            val value = params[spec.name]?.trim().takeUnless { it.isNullOrEmpty() } ?: spec.default.takeIf { it.isNotEmpty() }
            if (value == null) {
                require(!spec.required) { "param '${spec.name}' is required" }
                continue
            }
            checkValue(spec, value)
            result[spec.name] = value
        }
        return result
    }

    private fun checkValue(
        spec: JobParamSpec,
        value: String,
    ) {
        when (spec.type) {
            JobParamSpec.Type.BOOL -> {
                require(value == "true" || value == "false") { "param '${spec.name}' must be true or false" }
            }

            JobParamSpec.Type.INT -> {
                require(value.toLongOrNull() != null) { "param '${spec.name}' must be a whole number" }
            }

            JobParamSpec.Type.STRING -> {
                requireChoice(spec, value)
            }

            JobParamSpec.Type.STRING_LIST -> {
                value
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { requireChoice(spec, it) }
            }
        }
    }

    private fun requireChoice(
        spec: JobParamSpec,
        value: String,
    ) = require(spec.choices.isEmpty() || value in spec.choices) {
        "param '${spec.name}' must be one of ${spec.choices.joinToString(", ")}"
    }
}
