// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.io.TempDir
import panelarr.mihonsource.secret.SecretBox
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NetworkUnitTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `rate limiter spaces requests after the burst`() {
        var now = 0L
        val slept = mutableListOf<Long>()
        val limiter =
            RateLimiter(2, TimeUnit.SECONDS.toNanos(1), burst = 0, nanoTime = { now }, sleep = {
                slept += it
                now += it
            })
        repeat(2) { assertEquals(0, limiter.acquire()) }
        assertEquals(TimeUnit.MILLISECONDS.toNanos(500), limiter.acquire())
        now += TimeUnit.SECONDS.toNanos(10)
        assertEquals(0, limiter.acquire())
    }

    @Test
    fun `cookie jar persists and expires cookies`() {
        val url = "https://example.com/a".toHttpUrl()
        val file = dir.resolve("cookies.json")
        var now = 1_000_000L
        val jar = FileCookieJar(file) { now }
        jar.saveFromResponse(
            url,
            listOf(
                Cookie
                    .Builder()
                    .name("keep")
                    .value("1")
                    .domain("example.com")
                    .expiresAt(now + 60_000)
                    .build(),
                Cookie
                    .Builder()
                    .name("short")
                    .value("2")
                    .domain("example.com")
                    .expiresAt(now + 10)
                    .build(),
            ),
        )
        assertEquals(setOf("keep", "short"), jar.loadForRequest(url).map { it.name }.toSet())
        now += 100
        assertEquals(listOf("keep"), FileCookieJar(file) { now }.loadForRequest(url).map { it.name })
        assertEquals(1, jar.remove(url, listOf("keep")))
    }

    @Test
    fun `secrets round-trip and the key is stable`() {
        val box = SecretBox.load(dir.resolve("secret.key"), null)
        val sealed = box.encrypt("hunter2")
        assertNotEquals("hunter2", sealed)
        assertTrue(sealed.startsWith("enc:v1:"))
        assertEquals("hunter2", SecretBox.load(dir.resolve("secret.key"), null).decrypt(sealed))
        assertNotEquals(sealed, box.encrypt("hunter2"))
        assertFalse("hunter2" in Files.readString(dir.resolve("secret.key")))
    }

    @Test
    fun `profiles resolve pushed, then assigned, then default`() {
        val store = FileIntegrationsStore(dir)
        val resolver = ProfileResolver(store, SecretBox.load(dir.resolve("secret.key"), null))
        assertEquals(EffectiveProfile.Origin.NONE, resolver.resolve(1).origin)

        store.putProfile(ProfileSettings(id = "default", name = "Default", concurrency = 2))
        store.putProfile(ProfileSettings(id = "special", name = "Special", proxy = ProxySettings("socks5://proxy:1080")))
        store.setDefaultProfile("default")
        assertEquals(EffectiveProfile.Origin.DEFAULT, resolver.resolve(1).origin)

        store.assign(1, "special")
        val assigned = resolver.resolve(1)
        assertEquals(EffectiveProfile.Origin.ASSIGNED, assigned.origin)
        assertEquals(java.net.Proxy.Type.SOCKS, assigned.proxy?.type)
        assertEquals(1080, assigned.proxy?.port)

        store.setPushed(1, PushedProfile(ProfileSettings(id = "pushed", name = "host")))
        assertEquals(EffectiveProfile.Origin.PUSHED, resolver.resolve(1).origin)

        store.deleteProfile("special")
        store.setPushed(1, null)
        assertEquals(
            EffectiveProfile.Origin.DEFAULT,
            FileIntegrationsStore(dir)
                .let {
                    ProfileResolver(it, SecretBox.load(dir.resolve("secret.key"), null)).resolve(1)
                }.origin,
        )
    }

    @Test
    fun `invalid settings are rejected`() {
        val store = FileIntegrationsStore(dir)
        assertFailsWith<IllegalArgumentException> { store.putProfile(ProfileSettings(id = "Bad Id", name = "x")) }
        assertFailsWith<IllegalArgumentException> { store.putProfile(ProfileSettings(id = "p", name = "x", solverId = "missing")) }
        assertFailsWith<IllegalArgumentException> { ProxyUrls.parse("ftp://host") }
        assertFailsWith<IllegalArgumentException> { ProxyUrls.parse("http://user:pass@host:1") }
        store.putSolver(SolverSettings("fs", "FS", SolverSettings.Type.FLARESOLVERR, "http://fs:8191"))
        store.putProfile(ProfileSettings(id = "p", name = "x", solverId = "fs"))
        assertFailsWith<IllegalStateException> { store.deleteSolver("fs") }
    }
}
