// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import panelarr.mihonsource.jobs.BackgroundJob
import panelarr.mihonsource.jobs.JobNote
import panelarr.mihonsource.jobs.JobParamSpec
import panelarr.mihonsource.jobs.JobRunContext
import panelarr.mihonsource.jobs.JobSchedule
import java.time.Duration
import kotlin.coroutines.coroutineContext

/**
 * Refreshes the stores' indexes, so "update available" stays current. It never installs
 * anything: updates are installed only when someone asks. A store that fails to refresh
 * keeps its last good index, has the error recorded, and fails the run.
 */
class StoreRefreshJob(
    private val stores: StoreManager,
    interval: Duration,
) : BackgroundJob {
    override val id = "store-refresh"
    override val name = "Refresh stores"
    override val description = "Downloads every store's index again, so new extensions and updates show up. Never installs anything."
    override val group = "Stores"
    override val schedule = JobSchedule(interval.takeIf { !it.isZero && !it.isNegative }, "MIHON_STORE_REFRESH_INTERVAL")
    override val params =
        listOf(JobParamSpec(STORE_ID, JobParamSpec.Type.STRING, "Refresh only this store (its ID). All stores when empty."))

    override fun validate(params: Map<String, String>) {
        params[STORE_ID]?.let { stores.get(it) }
    }

    override suspend fun run(run: JobRunContext) {
        val selected = run.params[STORE_ID]?.let { listOf(stores.get(it)) } ?: stores.list()
        if (selected.isEmpty()) {
            run.skip("No stores to refresh")
            return
        }
        var failed = 0
        selected.forEachIndexed { index, store ->
            coroutineContext.ensureActive()
            run.progress("Refreshing", index.toLong(), selected.size.toLong(), "stores")
            try {
                stores.refresh(store.id)
                run.increment("stores")
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                failed++
                run.increment("failed")
                run.note(JobNote.Level.WARN, "${store.name}: ${e.message}", store.id)
            }
        }
        coroutineContext.ensureActive()
        run.progress("Refreshing", selected.size.toLong(), selected.size.toLong(), "stores")
        val updates = stores.available(updatesOnly = true).size
        run.count("updates", updates.toLong())
        val updateText = if (updates == 1) "1 update available" else "$updates updates available"
        if (failed > 0) {
            run.fail("$failed of ${selected.size} stores failed to refresh; $updateText")
        } else {
            run.message = "Refreshed ${selected.size} ${if (selected.size == 1) "store" else "stores"}; $updateText"
        }
    }

    private companion object {
        const val STORE_ID = "store_id"
    }
}
