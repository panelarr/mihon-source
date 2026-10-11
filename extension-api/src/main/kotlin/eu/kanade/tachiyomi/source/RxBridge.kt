// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.source

import kotlinx.coroutines.suspendCancellableCoroutine
import rx.Observable
import rx.Subscriber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Awaits the first (and only expected) value of an Rx 1 observable. */
internal suspend fun <T> Observable<T>.awaitSingle(): T =
    suspendCancellableCoroutine { cont ->
        val subscription =
            single().subscribe(
                object : Subscriber<T>() {
                    override fun onNext(t: T) {
                        if (cont.isActive) cont.resume(t)
                    }

                    override fun onError(e: Throwable) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onCompleted() = Unit
                },
            )
        cont.invokeOnCancellation { subscription.unsubscribe() }
    }
