// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import kotlinx.serialization.Serializable
import panelarr.mihonsource.storage.JsonFile
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** When the `source-health` job checks sources on its own, and which. */
data class HealthCheckSchedule(
    /** How often; null means no scheduled checks. */
    val interval: Duration?,
    /** Source languages covered; empty means all. */
    val langs: Set<String> = emptySet(),
    /** Sources per round, least recently checked first; null means all. */
    val sample: Int? = null,
    /** Set over the API rather than taken from the environment. */
    val fromApi: Boolean = false,
)

/**
 * The health-check schedule in effect: the one set over the API (kept in
 * `config/health-check-schedule.json`), or else [defaults] from the environment.
 */
class HealthCheckSchedules(
    file: Path,
    private val defaults: HealthCheckSchedule,
) {
    private val store = JsonFile(file, Stored.serializer())

    @Volatile
    private var override: HealthCheckSchedule? = store.read()?.toSchedule()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Serializable
    private data class Stored(
        val intervalSeconds: Long? = null,
        val langs: Set<String> = emptySet(),
        val sample: Int? = null,
    ) {
        fun toSchedule() = HealthCheckSchedule(intervalSeconds?.let(Duration::ofSeconds), langs, sample, fromApi = true)
    }

    fun current(): HealthCheckSchedule = override ?: defaults

    /** Replaces the schedule; null goes back to the environment's. */
    fun set(schedule: HealthCheckSchedule?) {
        synchronized(this) {
            if (schedule == null) {
                store.delete()
                override = null
            } else {
                require(schedule.interval == null || schedule.interval >= MIN_INTERVAL) { "the interval must be at least a minute" }
                require(schedule.sample == null || schedule.sample > 0) { "sample must be more than 0, or unset for all" }
                val stored = Stored(schedule.interval?.seconds, schedule.langs, schedule.sample)
                store.write(stored)
                override = stored.toSchedule()
            }
        }
        listeners.forEach { it() }
    }

    /** Calls [listener] after every change. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    companion object {
        val MIN_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
