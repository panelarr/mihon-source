// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Locks by key, each dropped once nobody holds or waits for it. */
class HostGates {
    private val gates = ConcurrentHashMap<String, Gate>()

    /**
     * Runs [block] while no other block runs for the same [key]: loads of one host in one
     * context take turns, as solves of one host do, so the second load finds the cookies the
     * first one earned (a passed challenge, say) instead of facing the same page again.
     */
    suspend fun <T> coalesced(
        key: String,
        block: suspend () -> T,
    ): T {
        val gate =
            gates.compute(key) { _, existing -> (existing ?: Gate()).also { it.waiting++ } }!!
        try {
            return gate.mutex.withLock { block() }
        } finally {
            gates.computeIfPresent(key) { _, existing -> existing.also { it.waiting-- }.takeIf { it.waiting > 0 } }
        }
    }

    /** A lock for [coalesced], dropped once nobody waits on it. Counted only inside the map's compute calls. */
    private class Gate {
        val mutex = Mutex()
        var waiting = 0
    }
}
