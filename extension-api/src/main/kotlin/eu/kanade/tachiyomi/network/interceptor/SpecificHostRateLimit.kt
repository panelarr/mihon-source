// SPDX-License-Identifier: GPL-3.0-or-later

@file:JvmName("SpecificHostRateLimitInterceptorKt")

package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Deprecated("Extensions bring their own rate limiting")
fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(httpUrl.host, permits, unit.toMillis(period)))

@Deprecated("Extensions bring their own rate limiting")
fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(httpUrl.host, permits, period.inWholeMilliseconds))

@Deprecated("Extensions bring their own rate limiting")
fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(url.toHttpUrl().host, permits, period.inWholeMilliseconds))
