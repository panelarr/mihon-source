// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.auth

import kotlinx.serialization.Serializable
import panelarr.mihonsource.storage.JsonFile
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A client as the API shows it: never with its key or key hash. */
data class ClientInfo(
    val id: String,
    val name: String,
    val scopes: Set<String>,
    val keyPrefix: String,
    val createdAt: Instant,
    val rotatedAt: Instant?,
    val lastUsedAt: Instant?,
)

/** A client together with its new API key, returned once after create or rotate. */
data class ClientWithKey(
    val client: ClientInfo,
    val apiKey: String,
)

class ClientNotFoundException(
    id: String,
) : NoSuchElementException("client '$id' not found")

/**
 * API clients and their hashed keys, kept in `auth/clients.json`. A presented key is
 * compared, in constant time, with each stored hash.
 */
class ClientRegistry(
    authDir: Path,
    private val clock: () -> Instant = Instant::now,
) {
    private val file = JsonFile(authDir.resolve("clients.json"), StoredClients.serializer(), private = true)
    private val lock = ReentrantLock()
    private val clients: MutableMap<String, StoredClient> = linkedMapOf()

    init {
        file.read()?.clients?.forEach { clients[it.id] = it }
    }

    fun list(): List<ClientInfo> = lock.withLock { clients.values.map { it.info() } }

    fun get(id: String): ClientInfo = lock.withLock { find(id).info() }

    fun create(
        name: String,
        scopes: Set<String>,
    ): ClientWithKey {
        val cleanName = validName(name)
        validScopes(scopes)
        return lock.withLock {
            val key = Secrets.randomToken(KEY_PREFIX)
            val client =
                StoredClient(
                    id = Secrets.randomId(),
                    name = cleanName,
                    scopes = scopes.toList(),
                    keyHash = Secrets.hash(key),
                    keyPrefix = key.take(KEY_PREFIX_SHOWN),
                    createdAt = clock().toString(),
                )
            clients[client.id] = client
            save()
            ClientWithKey(client.info(), key)
        }
    }

    fun update(
        id: String,
        name: String?,
        scopes: Set<String>?,
    ): ClientInfo {
        val cleanName = name?.let(::validName)
        scopes?.let(::validScopes)
        return lock.withLock {
            val current = find(id)
            val updated =
                current.copy(
                    name = cleanName ?: current.name,
                    scopes = scopes?.toList() ?: current.scopes,
                )
            clients[id] = updated
            save()
            updated.info()
        }
    }

    fun rotate(id: String): ClientWithKey =
        lock.withLock {
            val key = Secrets.randomToken(KEY_PREFIX)
            val updated =
                find(id).copy(
                    keyHash = Secrets.hash(key),
                    keyPrefix = key.take(KEY_PREFIX_SHOWN),
                    rotatedAt = clock().toString(),
                )
            clients[id] = updated
            save()
            ClientWithKey(updated.info(), key)
        }

    fun delete(id: String) {
        lock.withLock {
            if (clients.remove(id) == null) throw ClientNotFoundException(id)
            save()
        }
    }

    /** The client owning [key], or null. Also records when the client was last seen. */
    fun authenticate(key: String): Principal.Client? {
        if (!key.startsWith(KEY_PREFIX)) return null
        return lock.withLock {
            val client = clients.values.firstOrNull { Secrets.matches(key, it.keyHash) } ?: return null
            touch(client)
            Principal.Client(client.id, client.name, client.scopes.toSet())
        }
    }

    /** Persists last-used times coarsely, so busy clients don't cause a write per request. */
    private fun touch(client: StoredClient) {
        val now = clock()
        val last = client.lastUsedAt?.let(Instant::parse)
        if (last != null && Duration.between(last, now) < LAST_USED_RESOLUTION) return
        clients[client.id] = client.copy(lastUsedAt = now.toString())
        save()
    }

    private fun find(id: String) = clients[id] ?: throw ClientNotFoundException(id)

    private fun save() = file.write(StoredClients(clients.values.toList()))

    private fun validName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "name must not be empty" }
        require(trimmed.length <= MAX_NAME_LENGTH) { "name must be at most $MAX_NAME_LENGTH characters" }
        return trimmed
    }

    private fun validScopes(scopes: Set<String>) {
        require(scopes.isNotEmpty()) { "at least one scope is needed (${Scopes.all.joinToString()})" }
        val unknown = scopes - Scopes.all
        require(unknown.isEmpty()) { "unknown scopes: ${unknown.joinToString()} (known: ${Scopes.all.joinToString()})" }
    }

    @Serializable
    private data class StoredClients(
        val clients: List<StoredClient>,
    )

    @Serializable
    private data class StoredClient(
        val id: String,
        val name: String,
        val scopes: List<String>,
        val keyHash: String,
        val keyPrefix: String,
        val createdAt: String,
        val rotatedAt: String? = null,
        val lastUsedAt: String? = null,
    ) {
        fun info() =
            ClientInfo(
                id = id,
                name = name,
                scopes = scopes.toSet(),
                keyPrefix = keyPrefix,
                createdAt = Instant.parse(createdAt),
                rotatedAt = rotatedAt?.let(Instant::parse),
                lastUsedAt = lastUsedAt?.let(Instant::parse),
            )
    }

    companion object {
        const val KEY_PREFIX = "msk_"
        private const val KEY_PREFIX_SHOWN = 10
        private const val MAX_NAME_LENGTH = 100
        private val LAST_USED_RESOLUTION: Duration = Duration.ofMinutes(1)
    }
}
