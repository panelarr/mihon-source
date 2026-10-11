package panelarr.mihonsource.auth

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientRegistryTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a created key authenticates with its scopes`() {
        val registry = ClientRegistry(dir)
        val created = registry.create("reader", setOf(Scopes.SOURCES_READ))

        val principal = assertNotNull(registry.authenticate(created.apiKey))
        assertEquals(created.client.id, principal.id)
        assertEquals(setOf(Scopes.SOURCES_READ), principal.scopes)
        assertTrue(created.apiKey.startsWith(created.client.keyPrefix))
    }

    @Test
    fun `keys are stored only as hashes`() {
        val registry = ClientRegistry(dir)
        val created = registry.create("reader", setOf(Scopes.SOURCES_READ))

        val stored = Files.readString(dir.resolve("clients.json"))
        assertFalse(created.apiKey in stored)
        assertTrue(Secrets.hash(created.apiKey) in stored)
    }

    @Test
    fun `clients survive a restart`() {
        val key = ClientRegistry(dir).create("reader", setOf(Scopes.SOURCES_READ)).apiKey
        assertNotNull(ClientRegistry(dir).authenticate(key))
    }

    @Test
    fun `rotating replaces the key`() {
        val registry = ClientRegistry(dir)
        val created = registry.create("reader", setOf(Scopes.SOURCES_READ))
        val rotated = registry.rotate(created.client.id)

        assertNull(registry.authenticate(created.apiKey))
        assertNotNull(registry.authenticate(rotated.apiKey))
        assertNotNull(rotated.client.rotatedAt)
    }

    @Test
    fun `deleting a client revokes its key`() {
        val registry = ClientRegistry(dir)
        val created = registry.create("reader", setOf(Scopes.SOURCES_READ))
        registry.delete(created.client.id)

        assertNull(registry.authenticate(created.apiKey))
        assertFailsWith<ClientNotFoundException> { registry.delete(created.client.id) }
    }

    @Test
    fun `update changes name and scopes but keeps the key`() {
        val registry = ClientRegistry(dir)
        val created = registry.create("reader", setOf(Scopes.SOURCES_READ))
        registry.update(created.client.id, name = "host", scopes = setOf(Scopes.SOURCES_READ, Scopes.SOURCES_WRITE))

        val principal = assertNotNull(registry.authenticate(created.apiKey))
        assertEquals("host", principal.name)
        assertEquals(setOf(Scopes.SOURCES_READ, Scopes.SOURCES_WRITE), principal.scopes)
    }

    @Test
    fun `unknown or missing scopes and blank names are rejected`() {
        val registry = ClientRegistry(dir)
        assertFailsWith<IllegalArgumentException> { registry.create("x", setOf("root")) }
        assertFailsWith<IllegalArgumentException> { registry.create("x", emptySet()) }
        assertFailsWith<IllegalArgumentException> { registry.create("  ", setOf(Scopes.ADMIN)) }
    }

    @Test
    fun `unknown keys and admin-style tokens don't authenticate`() {
        val registry = ClientRegistry(dir)
        registry.create("reader", setOf(Scopes.SOURCES_READ))
        assertNull(registry.authenticate("msk_not-a-real-key"))
        assertNull(registry.authenticate("msa_whatever"))
    }

    @Test
    fun `last use is recorded`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val registry = ClientRegistry(dir) { now }
        val created = registry.create("reader", setOf(Scopes.SOURCES_READ))
        registry.authenticate(created.apiKey)
        assertEquals(now, registry.get(created.client.id).lastUsedAt)

        now = now.plusSeconds(3600)
        registry.authenticate(created.apiKey)
        assertEquals(now, ClientRegistry(dir).get(created.client.id).lastUsedAt)
    }
}
