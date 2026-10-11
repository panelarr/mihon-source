// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import kotlin.coroutines.CoroutineContext

/**
 * `Dispatchers.Main` on a plain JVM: runs coroutines on the thread behind
 * `Looper.getMainLooper()`, the same thread that [Handler]s post to. Registered as a
 * `MainDispatcherFactory` service, which kotlinx-coroutines looks up on first use of
 * `Dispatchers.Main`.
 */
@OptIn(InternalCoroutinesApi::class)
class MainLooperDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int = 0

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = MainLooperDispatcher.MAIN

    override fun hintOnError(): String = "The main looper thread of mihon-source failed to start"
}

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
internal class MainLooperDispatcher private constructor(
    private val invokeImmediately: Boolean,
) : MainCoroutineDispatcher(),
    Delay {
    private val handler = Handler(Looper.getMainLooper())

    override val immediate: MainCoroutineDispatcher
        get() = if (invokeImmediately) this else IMMEDIATE

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !invokeImmediately || !Looper.getMainLooper().isCurrentThread

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        handler.post(block)
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val resume = Runnable { with(continuation) { resumeUndispatched(Unit) } }
        handler.postDelayed(resume, timeMillis)
        continuation.invokeOnCancellation { handler.removeCallbacks(resume) }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle {
        handler.postDelayed(block, timeMillis)
        return DisposableHandle { handler.removeCallbacks(block) }
    }

    override fun toString(): String = if (invokeImmediately) "Dispatchers.Main.immediate" else "Dispatchers.Main"

    companion object {
        val MAIN = MainLooperDispatcher(invokeImmediately = false)
        private val IMMEDIATE = MainLooperDispatcher(invokeImmediately = true)
    }
}
