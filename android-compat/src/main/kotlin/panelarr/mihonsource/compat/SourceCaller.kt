// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/**
 * Which source the code running on this thread works for, so shared Android APIs
 * (`CookieManager`, `WebView`) can act for that source alone.
 *
 * The host sets it around each call into a source ([with], or [element] for coroutines),
 * and around requests of a source's HTTP client. Work posted to the main looper (through
 * `Handler` or `Dispatchers.Main`) runs with the source of the thread that posted it, so a
 * WebView an extension builds on the main looper still knows its source.
 *
 * Null means unknown: code that isn't tied to one source, or a thread the host doesn't see,
 * such as one the extension started itself.
 */
object SourceCaller {
    private val current = ThreadLocal<Long?>()

    /** The source the current thread works for, or null. */
    @JvmStatic
    fun current(): Long? = current.get()

    /** Runs [block] as working for [sourceId] (null: for no source), then restores the previous value. */
    @JvmStatic
    fun <T> with(
        sourceId: Long?,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(sourceId)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /** [task], made to run as working for the current thread's source, wherever it runs. */
    @JvmStatic
    fun bind(task: Runnable): Runnable = bind(current(), task)

    /** [task], made to run as working for [sourceId]. */
    @JvmStatic
    fun bind(
        sourceId: Long?,
        task: Runnable,
    ): Runnable = Runnable { with(sourceId) { task.run() } }

    /** A coroutine context element that keeps [sourceId] on whichever thread the coroutine runs. */
    fun element(sourceId: Long?): ThreadContextElement<Long?> = current.asContextElement(sourceId)
}
