// SPDX-License-Identifier: GPL-3.0-or-later

@file:JvmName("RateLimitInterceptorKt")

package eu.kanade.tachiyomi.network.interceptor

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Limits requests of this client to [permits] per [period] [unit]s. */
@Deprecated("Extensions bring their own rate limiting")
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(null, permits, unit.toMillis(period)))

@Deprecated("Extensions bring their own rate limiting")
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(null, permits, period.inWholeMilliseconds))
