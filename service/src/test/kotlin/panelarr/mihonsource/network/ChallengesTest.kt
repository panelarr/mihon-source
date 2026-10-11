// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChallengesTest {
    private fun response(
        code: Int,
        body: String = "",
        vararg headers: Pair<String, String>,
    ): Response =
        Response
            .Builder()
            .request(Request.Builder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .body(body.toResponseBody())
            .build()

    @Test
    fun `cloudflare server header`() {
        assertTrue(Challenges.isChallenge(response(503, "", "Server" to "cloudflare")))
        assertTrue(Challenges.isChallenge(response(403, "", "Server" to "Cloudflare-nginx")))
    }

    @Test
    fun `cf-mitigated header without a server header`() {
        assertTrue(Challenges.isChallenge(response(403, "", "cf-mitigated" to "challenge")))
    }

    @Test
    fun `challenge page markers when the server header is rewritten`() {
        assertTrue(Challenges.isChallenge(response(403, "<html><head><title>Just a moment...</title></head></html>", "Server" to "nginx")))
        assertTrue(Challenges.isChallenge(response(503, "<script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate\"></script>")))
        assertTrue(Challenges.isChallenge(response(403, "<div id=\"cf-chl-widget\"></div>")))
    }

    @Test
    fun `plain errors and other statuses are not challenges`() {
        assertFalse(Challenges.isChallenge(response(403, "<h1>Forbidden</h1>", "Server" to "nginx")))
        assertFalse(Challenges.isChallenge(response(404, "<title>Just a moment...</title>", "Server" to "cloudflare")))
        assertFalse(Challenges.isChallenge(response(200, "", "cf-mitigated" to "challenge")))
    }

    @Test
    fun `peeking leaves the body readable`() {
        val response = response(403, "<title>Just a moment...</title>")
        assertTrue(Challenges.isChallenge(response))
        assertTrue("Just a moment" in response.body.string())
    }
}
