// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.healthcheck

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthcheckTest {
    private fun serving(status: Int): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/readyz") { exchange ->
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }

    @Test
    fun `exits 0 only when ready`() {
        val ready = serving(200)
        val starting = serving(503)
        try {
            assertEquals(0, healthcheck("http://127.0.0.1:${ready.address.port}/readyz"))
            assertEquals(1, healthcheck("http://127.0.0.1:${starting.address.port}/readyz"))
        } finally {
            ready.stop(0)
            starting.stop(0)
        }
        // Nothing listening.
        assertEquals(1, healthcheck("http://127.0.0.1:1/readyz"))
    }

    @Test
    fun `the URL follows the port, unless given`() {
        assertEquals("http://127.0.0.1:8902/readyz", healthcheckUrl(emptyMap()))
        assertEquals("http://127.0.0.1:9000/readyz", healthcheckUrl(mapOf("MIHON_PORT" to "9000")))
        assertEquals(
            "http://other:1/healthz",
            healthcheckUrl(
                mapOf(
                    "MIHON_HEALTHCHECK_URL" to "http://other:1/healthz",
                    "MIHON_PORT" to "9000",
                ),
            ),
        )
    }
}
