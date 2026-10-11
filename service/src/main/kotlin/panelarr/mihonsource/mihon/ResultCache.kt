// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import com.google.protobuf.MessageLite
import io.grpc.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import panelarr.mihonsource.metrics.Metrics
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * A short-lived, size-bounded cache of source API results, so repeated and concurrent
 * identical calls don't each reach the site. Identical calls in flight at the same time
 * share one call. Errors aren't cached. Keys are the source, the operation and a hash of
 * the request; they hold no credentials or caller identity.
 *
 * A caller can bypass it (`Cache-Control: no-cache`); the fresh result then replaces the
 * cached one. Everything for a source is dropped when its settings, network profile or
 * extension change.
 */
class ResultCache(
    private val ttls: Map<Kind, Duration>,
    private val maxBytes: Long,
    private val metrics: Metrics? = null,
    private val now: () -> Long = System::nanoTime,
) {
    /** How long results live depends on how fast they change at the source. */
    enum class Kind { LIST, DETAILS, PAGES, FILTERS }

    private class Entry(
        val value: MessageLite,
        val size: Int,
        val expiresAt: Long,
    )

    // Insertion-ordered for LRU eviction; guarded by `this`.
    private val entries = LinkedHashMap<String, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var bytes = 0L
    private val inFlight = ConcurrentHashMap<String, Deferred<MessageLite>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : MessageLite> get(
        sourceId: Long,
        operation: String,
        kind: Kind,
        request: MessageLite,
        load: suspend () -> T,
    ): T {
        val ttl = ttls[kind]?.takeIf { !it.isZero && !it.isNegative } ?: return load()
        val key = "$sourceId:$operation:${digest(request)}"
        val bypass = BYPASS.get() == true
        if (!bypass) {
            lookup(key)?.let { cached ->
                metrics?.cache(operation, "hit")
                return cached as T
            }
            inFlight[key]?.let { shared ->
                metrics?.cache(operation, "shared")
                return shared.await() as T
            }
        }
        metrics?.cache(operation, if (bypass) "bypass" else "miss")
        val mine = CompletableDeferred<MessageLite>()
        val running = if (bypass) null else inFlight.putIfAbsent(key, mine)
        if (running != null) return running.await() as T
        try {
            val value = load()
            store(key, value, ttl)
            mine.complete(value)
            return value
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    /** Drops everything cached for [sourceId]. */
    @Synchronized
    fun invalidate(sourceId: Long) {
        val prefix = "$sourceId:"
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (key.startsWith(prefix)) {
                bytes -= entry.size
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }

    @Synchronized
    private fun lookup(key: String): MessageLite? {
        val entry = entries[key] ?: return null
        if (now() >= entry.expiresAt) {
            entries.remove(key)
            bytes -= entry.size
            return null
        }
        return entry.value
    }

    @Synchronized
    private fun store(
        key: String,
        value: MessageLite,
        ttl: Duration,
    ) {
        val size = value.serializedSize + key.length
        if (size > maxBytes) return
        entries.remove(key)?.let { bytes -= it.size }
        entries[key] = Entry(value, size, now() + ttl.toNanos())
        bytes += size
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            bytes -= iterator.next().value.size
            iterator.remove()
        }
    }

    private fun digest(request: MessageLite): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toByteArray())).take(DIGEST_CHARS)

    companion object {
        /** Set on the gRPC context for calls that asked to bypass the cache. */
        val BYPASS: Context.Key<Boolean> = Context.key("cache-bypass")
        private const val INITIAL_CAPACITY = 256
        private const val LOAD_FACTOR = 0.75f
        private const val DIGEST_CHARS = 32
    }
}
