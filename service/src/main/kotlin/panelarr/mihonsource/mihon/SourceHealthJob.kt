// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import panelarr.mihonsource.jobs.BackgroundJob
import panelarr.mihonsource.jobs.JobNote
import panelarr.mihonsource.jobs.JobParamSpec
import panelarr.mihonsource.jobs.JobRunContext
import panelarr.mihonsource.jobs.JobSchedule
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Checks sources and records the result as their health. A round covers the sources
 * whose scheduled checks are on, limited to the schedule's languages (all when empty) and
 * to a sample of them (all when null); [schedules] says which, and how often. The sample
 * takes the least recently checked sources first, ties in random order, so rounds rotate
 * through every eligible source. Given source IDs
 * override all of that. Sources are checked in random order, two at a time, each after a
 * short random pause, so checks don't arrive at sites together. Checks use each source's
 * own client, so its rate limits apply. A run for given sources also notes each step's
 * result, so whoever asked sees what worked.
 */
class SourceHealthJob(
    private val sources: MihonSources,
    private val prober: SourceProber,
    private val health: SourceHealthService,
    private val schedules: HealthCheckSchedules,
    private val random: Random = Random.Default,
    private val pause: Duration = MAX_PAUSE,
) : BackgroundJob {
    override val id = ID
    override val name = "Check sources"
    override val description =
        "Fetches from each source to see whether it works, and records the result as the source's health. " +
            "Rounds skip sources whose checks are turned off, and take the least recently checked sources first."
    override val group = "Sources"
    override val schedule: JobSchedule
        get() =
            schedules.current().let {
                JobSchedule(
                    it.interval?.takeIf { interval -> !interval.isZero && !interval.isNegative },
                    if (it.fromApi) "SetHealthCheckSchedule" else "MIHON_HEALTH_CHECK_INTERVAL",
                )
            }

    // The language choices are the installed sources' languages, so they're worked out each time.
    override val params: List<JobParamSpec>
        get() {
            val (_, langs, sample) = schedules.current()
            return listOf(
                JobParamSpec(SOURCE_IDS, JobParamSpec.Type.STRING_LIST, "Check only these sources (IDs). Overrides langs and sample."),
                JobParamSpec(
                    LANGS,
                    JobParamSpec.Type.STRING_LIST,
                    "Only sources in these languages; \"$ALL\" for every language. Defaults to MIHON_HEALTH_CHECK_LANGS.",
                    default = langs.sorted().joinToString(","),
                    choices = listOf(ALL) + (sources.all().map { it.lang }.toSet() + langs).sorted(),
                ),
                JobParamSpec(
                    SAMPLE,
                    JobParamSpec.Type.INT,
                    "How many sources to check, least recently checked first; 0 for all. Defaults to MIHON_HEALTH_CHECK_SAMPLE.",
                    default = sample?.toString().orEmpty(),
                ),
                JobParamSpec(
                    STEPS,
                    JobParamSpec.Type.STRING_LIST,
                    "What to check. Later steps include the ones they need, e.g. image checks pages and chapters.",
                    default = "popular",
                    choices = CheckStepKind.entries.map { it.name.lowercase() },
                ),
            )
        }

    override fun validate(params: Map<String, String>) {
        params[SOURCE_IDS]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { sources.get(it) }
        params[SAMPLE]?.let { require(it.toLong() >= 0) { "sample must be 0 or more" } }
    }

    /** Given source IDs make langs and sample moot: they're left out of the run's params. */
    override fun normalize(params: Map<String, String>): Map<String, String> =
        if (params[SOURCE_IDS].isNullOrBlank()) params else params - LANGS - SAMPLE

    /** The sources a run covers, and how many were eligible. */
    internal fun select(run: JobRunContext): Pair<List<MihonSource>, Int> {
        val ids = run.list(SOURCE_IDS)
        if (ids.isNotEmpty()) return ids.map { sources.get(it) }.let { it to it.size }
        val wanted =
            run
                .list(LANGS)
                .toSet()
                .takeUnless { ALL in it }
                .orEmpty()
        val eligible = sources.all().filter { health.enabled(it.id) && (wanted.isEmpty() || it.lang in wanted) }
        val size = run.params[SAMPLE]?.toInt()?.takeIf { it > 0 } ?: return eligible to eligible.size
        // Never checked first, then oldest check first; shuffling first breaks ties at random.
        val rotation = eligible.shuffled(random).sortedBy { health.get(it.id)?.checkedAt?.let(Instant::parse) ?: Instant.MIN }
        return rotation.take(size) to eligible.size
    }

    override suspend fun run(run: JobRunContext) {
        val (selected, eligible) = select(run)
        if (selected.isEmpty()) {
            run.skip("No sources to check")
            return
        }
        run.count("eligible", eligible.toLong())
        val steps = run.list(STEPS).map { CheckStepKind.valueOf(it.uppercase()) }.toSet()
        val targeted = run.list(SOURCE_IDS).isNotEmpty()
        val total = selected.size.toLong()
        val done = AtomicLong()
        run.count("sources", total)
        run.progress("Checking", 0, total, "sources")
        val limit = Semaphore(CONCURRENCY)
        coroutineScope {
            selected
                .shuffled(random)
                .map { source ->
                    async {
                        limit.withPermit {
                            if (!pause.isZero) delay(random.nextLong(pause.toMillis() + 1))
                            val result = prober.probe(source, steps)
                            ensureActive()
                            health.record(result)
                            if (targeted) noteSteps(run, result)
                            if (result.ok) {
                                run.increment("ok")
                            } else {
                                run.increment("failing")
                                val failure = result.failure
                                // A run for given sources noted the failing step already.
                                if (!targeted) {
                                    run.note(
                                        JobNote.Level.WARN,
                                        "${source.name} (${source.lang}): ${failure?.reason ?: "failed"}" +
                                            failure?.message?.let { " – $it" }.orEmpty(),
                                        source.id.toString(),
                                    )
                                }
                            }
                            run.progress("Checking", done.incrementAndGet(), total, "sources")
                        }
                    }
                }.awaitAll()
        }
        val ok = run.counts()["ok"] ?: 0
        val checked = if (ok == total) "${sourceCount(total)} OK" else "$ok of ${sourceCount(total)} OK"
        run.message = if (eligible > total) "$checked ($total of $eligible eligible)" else checked
    }

    /** Notes each step of [result]: what it found, or why it failed or was skipped. */
    private fun noteSteps(
        run: JobRunContext,
        result: SourceCheckResult,
    ) {
        val source = result.source
        for (step in result.steps) {
            val (level, outcome) =
                when {
                    step.skipped -> JobNote.Level.INFO to "skipped"
                    step.ok -> JobNote.Level.INFO to "ok"
                    else -> JobNote.Level.WARN to (step.reason ?: "failed")
                }
            val detail = listOfNotNull(step.detail?.takeIf { it.isNotBlank() }, step.message?.takeIf { it.isNotBlank() })
            val took = if (step.skipped) "" else " (${step.duration.toMillis()} ms)"
            run.note(
                level,
                "${source.name} (${source.lang}) ${step.step.name.lowercase()}: $outcome$took" +
                    detail.joinToString(" – ").let { if (it.isEmpty()) "" else " – $it" },
                source.id.toString(),
            )
        }
    }

    private fun sourceCount(count: Long) = if (count == 1L) "1 source" else "$count sources"

    companion object {
        const val ID = "source-health"
        const val SOURCE_IDS = "source_ids"
        const val LANGS = "langs"
        const val SAMPLE = "sample"
        const val ALL = "all"
        const val STEPS = "steps"
        const val CONCURRENCY = 2
        val MAX_PAUSE: Duration = Duration.ofSeconds(2)
    }
}
