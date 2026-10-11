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
 * whose scheduled checks are on, limited to [langs] (all when empty) and to a [sample] of
 * them (all when null). The sample takes the least recently checked sources first, ties
 * in random order, so rounds rotate through every eligible source. Given source IDs
 * override all of that. Sources are checked in random order, two at a time, each after a
 * short random pause, so checks don't arrive at sites together. Checks use each source's
 * own client, so its rate limits apply.
 */
class SourceHealthJob(
    private val sources: MihonSources,
    private val prober: SourceProber,
    private val health: SourceHealthService,
    interval: Duration?,
    private val langs: Set<String> = emptySet(),
    private val sample: Int? = null,
    private val random: Random = Random.Default,
    private val pause: Duration = MAX_PAUSE,
) : BackgroundJob {
    override val id = "source-health"
    override val name = "Check sources"
    override val description =
        "Fetches from each source to see whether it works, and records the result as the source's health. " +
            "Rounds skip sources whose checks are turned off, and take the least recently checked sources first."
    override val group = "Sources"
    override val schedule = JobSchedule(interval?.takeIf { !it.isZero && !it.isNegative }, "MIHON_HEALTH_CHECK_INTERVAL")

    // The language choices are the installed sources' languages, so they're worked out each time.
    override val params: List<JobParamSpec>
        get() =
            listOf(
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
                            if (result.ok) {
                                run.increment("ok")
                            } else {
                                run.increment("failing")
                                val failure = result.failure
                                run.note(
                                    JobNote.Level.WARN,
                                    "${source.name} (${source.lang}): ${failure?.reason ?: "failed"}" +
                                        failure?.message?.let { " – $it" }.orEmpty(),
                                    source.id.toString(),
                                )
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

    private fun sourceCount(count: Long) = if (count == 1L) "1 source" else "$count sources"

    private companion object {
        const val SOURCE_IDS = "source_ids"
        const val LANGS = "langs"
        const val SAMPLE = "sample"
        const val ALL = "all"
        const val STEPS = "steps"
        const val CONCURRENCY = 2
        val MAX_PAUSE: Duration = Duration.ofSeconds(2)
    }
}
