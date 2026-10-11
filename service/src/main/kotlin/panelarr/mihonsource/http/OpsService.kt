// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.http

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService

/**
 * Operational endpoints for orchestrators and scrapers:
 * - `GET /healthz`: liveness, 200 while the process serves requests. No credentials.
 * - `GET /readyz`: readiness, 200 once installed extensions are loaded. No credentials.
 * - `GET /metrics`: Prometheus metrics. On the API port it needs the `metrics` scope;
 *   a separate metrics port (MIHON_METRICS_PORT) serves it without credentials.
 */
object OpsService {
    fun liveness(): HttpService = HttpService { _, _ -> HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, """{"status":"ok"}""") }

    fun readiness(ready: () -> Boolean): HttpService =
        HttpService { _, _ ->
            if (ready()) {
                HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, """{"status":"ready"}""")
            } else {
                HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.JSON_UTF_8, """{"status":"starting"}""")
            }
        }

    fun metrics(scrape: () -> String): HttpService =
        HttpService { _, _ ->
            HttpResponse.of(HttpStatus.OK, MediaType.parse("text/plain; version=0.0.4; charset=utf-8"), scrape())
        }
}
