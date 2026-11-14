// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import kotlinx.serialization.Serializable
import panelarr.mihonsource.metrics.Metrics
import panelarr.mihonsource.storage.JsonFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-source health: the last check of each source, from on-demand checks or from the
 * `source-health` job ([SourceHealthJob]), and which sources scheduled checks skip.
 * Kept in `config/source-health.json`.
 */
class SourceHealthService(
    file: Path,
    private val metrics: Metrics?,
) {
    private val store = JsonFile(file, State.serializer())
    private val state: State = store.read() ?: State()
    private val results = ConcurrentHashMap<Long, Health>(state.results.mapKeys { it.key.toLong() })
    private val disabled: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>().apply { addAll(state.disabled) }

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
        // NEEDS_WEBVIEW comes from the prober, which knows whether a browser is configured.
        val reason = failure?.reason
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

    @Synchronized
    private fun save() = store.write(State(results.mapKeys { it.key.toString() }, disabled.toSet()))
}
