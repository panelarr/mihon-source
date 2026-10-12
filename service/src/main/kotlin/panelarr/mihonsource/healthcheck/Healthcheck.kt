// SPDX-License-Identifier: GPL-3.0-or-later

@file:JvmName("Healthcheck")

package panelarr.mihonsource.healthcheck

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Probes a running mihon-source for container health checks: exits 0 when its readiness
 * endpoint answers 200, 1 otherwise. The image runs it with `java` directly and a small
 * heap, since the JRE image has no curl. Also available as `mihon-source healthcheck`.
 */
fun main(args: Array<String>): Unit = exitProcess(healthcheck(args.firstOrNull() ?: healthcheckUrl(System.getenv())))

/** `MIHON_HEALTHCHECK_URL`, else /readyz on `MIHON_PORT` (8902) of this host. */
fun healthcheckUrl(env: Map<String, String>): String =
    env["MIHON_HEALTHCHECK_URL"]?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:${env["MIHON_PORT"]?.takeIf { it.isNotBlank() } ?: "8902"}/readyz"

/** 0 when [url] answers 200 within a few seconds, 1 otherwise. */
fun healthcheck(url: String): Int =
    try {
        val client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build()
        val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        if (status == HTTP_OK) 0 else 1.also { System.err.println("$url answered HTTP $status") }
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        System.err.println("$url: ${e.message ?: e.javaClass.simpleName}")
        1
    }

private val TIMEOUT: Duration = Duration.ofSeconds(3)
private const val HTTP_OK = 200
