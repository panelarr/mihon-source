// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import panelarr.mihonsource.metrics.Metrics
import panelarr.mihonsource.storage.JsonFile
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Per-source health: the last check of each source, from on-demand checks or from the
 * scheduled self-check. The schedule is off unless an interval is configured; then each
 * enabled source gets its first page of popular fetched once per interval, at a random
 * moment within it so checks don't arrive at sites together. Checks use the source's own
 * client, so its rate limits apply. Kept in `config/source-health.json`.
 */
class SourceHealthService(
    file: Path,
    private val sources: MihonSources,
    private val prober: SourceProber,
    private val metrics: Metrics?,
    private val interval: Duration?,
    private val random: Random = Random.Default,
) {
    private val log = LoggerFactory.getLogger(SourceHealthService::class.java)
    private val store = JsonFile(file, State.serializer())
    private val state: State = store.read() ?: State()
    private val results = ConcurrentHashMap<Long, Health>(state.results.mapKeys { it.key.toLong() })
    private val disabled: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>().apply { addAll(state.disabled) }
    private var job: Job? = null

    @Serializable
    data class Health(
        val ok: Boolean,
        val reason: String? = null,
        val message: String? = null,
        val latencyMillis: Long = 0,
        val checkedAt: String,
    )

    @Serializable
    private data class State(
        val results: Map<String, Health> = emptyMap(),
        val disabled: Set<Long> = emptySet(),
    )

    fun get(sourceId: Long): Health? = results[sourceId]

    fun enabled(sourceId: Long): Boolean = sourceId !in disabled

    fun setEnabled(
        sourceId: Long,
        enabled: Boolean,
    ) {
        if (enabled) disabled -= sourceId else disabled += sourceId
        save()
    }

    /** Records a finished check as the source's health. */
    fun record(result: SourceCheckResult) {
        val failure = result.failure
        val reason = failure?.reason?.let { if (result.source.needsWebView) "NEEDS_WEBVIEW" else it }
        results[result.source.id] =
            Health(
                ok = result.ok,
                reason = reason,
                message = failure?.message,
                latencyMillis = result.latency.toMillis(),
                checkedAt = result.checkedAt.toString(),
            )
        metrics?.sourceHealth(result.source.id, result.ok)
        save()
    }

    /** Starts the scheduled self-check, when an interval is configured. */
    fun start(scope: CoroutineScope) {
        val every = interval?.takeIf { !it.isZero && !it.isNegative } ?: return
        log.info("Checking source health every {}", every)
        job =
            scope.launch {
                while (isActive) {
                    val round = sources.all().filter { enabled(it.id) }
                    if (round.isEmpty()) {
                        delay(every.toMillis())
                        continue
                    }
                    // Spread the round over the interval, in random order.
                    val gap = every.toMillis() / round.size
                    for (source in round.shuffled(random)) {
                        delay(random.nextLong(gap / 2, gap.coerceAtLeast(1) + 1))
                        if (!isActive) break
                        val current = sources.all().firstOrNull { it.id == source.id } ?: continue
                        if (!enabled(current.id)) continue
                        record(prober.probe(current, setOf(CheckStepKind.POPULAR)))
                    }
                }
            }
    }

    fun stop() {
        job?.cancel()
    }

    @Synchronized
    private fun save() = store.write(State(results.mapKeys { it.key.toString() }, disabled.toSet()))
}
