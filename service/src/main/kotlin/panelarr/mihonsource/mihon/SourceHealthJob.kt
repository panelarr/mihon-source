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
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Checks sources and records the result as their health. A round covers the sources
 * whose scheduled checks are on, or the sources given. Sources are checked in random
 * order, two at a time, each after a short random pause, so checks don't arrive at sites
 * together. Checks use each source's own client, so its rate limits apply.
 */
class SourceHealthJob(
    private val sources: MihonSources,
    private val prober: SourceProber,
    private val health: SourceHealthService,
    interval: Duration?,
    private val random: Random = Random.Default,
    private val pause: Duration = MAX_PAUSE,
) : BackgroundJob {
    override val id = "source-health"
    override val name = "Check sources"
    override val description =
        "Fetches from each source to see whether it works, and records the result as the source's health. " +
            "Scheduled rounds skip sources whose checks are turned off."
    override val group = "Sources"
    override val schedule = JobSchedule(interval?.takeIf { !it.isZero && !it.isNegative }, "MIHON_HEALTH_CHECK_INTERVAL")
    override val params =
        listOf(
            JobParamSpec(SOURCE_IDS, JobParamSpec.Type.STRING_LIST, "Check only these sources (IDs). Sources with checks on when empty."),
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
    }

    override suspend fun run(run: JobRunContext) {
        val ids = run.list(SOURCE_IDS)
        val selected = if (ids.isEmpty()) sources.all().filter { health.enabled(it.id) } else ids.map { sources.get(it) }
        if (selected.isEmpty()) {
            run.skip("No sources to check")
            return
        }
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
        run.message = if (ok == total) "${sourceCount(total)} OK" else "$ok of ${sourceCount(total)} OK"
    }

    private fun sourceCount(count: Long) = if (count == 1L) "1 source" else "$count sources"

    private companion object {
        const val SOURCE_IDS = "source_ids"
        const val STEPS = "steps"
        const val CONCURRENCY = 2
        val MAX_PAUSE: Duration = Duration.ofSeconds(2)
    }
}
