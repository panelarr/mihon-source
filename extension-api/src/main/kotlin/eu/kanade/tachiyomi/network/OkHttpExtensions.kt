// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import rx.Observable
import rx.Producer
import rx.Subscription
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Deprecated("Use await")
fun Call.asObservable(): Observable<Response> =
    Observable.unsafeCreate { subscriber ->
        val call = clone()
        val requested = AtomicBoolean()
        val producer =
            object : Producer, Subscription {
                override fun request(n: Long) {
                    if (n == 0L || !requested.compareAndSet(false, true)) return
                    try {
                        val response = call.execute()
                        if (!subscriber.isUnsubscribed) {
                            subscriber.onNext(response)
                            subscriber.onCompleted()
                        }
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        if (!subscriber.isUnsubscribed) subscriber.onError(e)
                    }
                }

                override fun unsubscribe() = call.cancel()

                override fun isUnsubscribed(): Boolean = call.isCanceled()
            }
        subscriber.add(producer)
        subscriber.setProducer(producer)
    }

@Deprecated("Use awaitSuccess")
fun Call.asObservableSuccess(): Observable<Response> =
    asObservable().doOnNext { response ->
        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.code)
        }
    }

suspend fun Call.await(): Response {
    val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
    return suspendCancellableCoroutine { continuation ->
        val callback =
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, value, _ -> value.body.close() }
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isCancelled) return
                    val exception = IOException(e.message, e).apply { stackTrace = callStack }
                    continuation.resumeWithException(exception)
                }
            }
        enqueue(callback)
        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Throwable,
            ) {
                // Nothing to do; the call is gone.
            }
        }
    }
}

suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code)
    }
    return response
}
