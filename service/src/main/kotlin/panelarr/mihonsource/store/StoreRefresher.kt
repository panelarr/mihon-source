// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.random.Random

/**
 * Refreshes every store's index on a schedule, so "update available" stays current. It
 * never installs anything: updates are installed only when someone asks. Each round
 * starts after the interval plus up to 10% random delay, so many instances don't hit a
 * store at the same moment. A failed refresh is recorded on the store and retried next
 * round.
 */
class StoreRefresher(
    private val stores: StoreManager,
    private val interval: Duration,
    private val random: Random = Random.Default,
) {
    private val log = LoggerFactory.getLogger(StoreRefresher::class.java)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (interval.isZero || interval.isNegative) return
        log.info("Refreshing extension stores every {}", interval)
        job =
            scope.launch {
                while (isActive) {
                    val jitter = random.nextLong(interval.toMillis() / JITTER_DIVISOR + 1)
                    delay(interval.toMillis() + jitter)
                    refreshAll()
                }
            }
    }

    /** One round: refresh every store. Returns how many installed extensions have updates. */
    suspend fun refreshAll(): Int {
        for (store in stores.list()) {
            runCatching { stores.refresh(store.id) }
                .onFailure { log.warn("Refreshing store {} failed: {}", store.name, it.message) }
        }
        val updates = stores.available(updatesOnly = true).size
        if (updates > 0) log.info("{} extension update(s) available", updates)
        return updates
    }

    fun stop() {
        job?.cancel()
    }

    private companion object {
        const val JITTER_DIVISOR = 10
    }
}
