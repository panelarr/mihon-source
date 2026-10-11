// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import io.grpc.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import panelarr.source.v1.PopularRequest
import panelarr.source.v1.PopularResponse
import panelarr.source.v1.popularRequest
import panelarr.source.v1.popularResponse
import panelarr.source.v1.series
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResultCacheTest {
    private var clock = 0L
    private val loads = AtomicInteger()

    private fun cache(maxBytes: Long = 1_000_000) = ResultCache(mapOf(ResultCache.Kind.LIST to Duration.ofMinutes(5)), maxBytes) { clock }

    private fun request(page: Int): PopularRequest =
        popularRequest {
            sourceId = "1"
            this.page = page
        }

    private suspend fun ResultCache.popular(
        page: Int,
        sourceId: Long = 1,
    ): PopularResponse =
        get(sourceId, "popular", ResultCache.Kind.LIST, request(page)) {
            loads.incrementAndGet()
            popularResponse { series += series { key = "s$page" } }
        }

    @Test
    fun `results are reused until they expire`() =
        runBlocking {
            val cache = cache()
            cache.popular(1)
            cache.popular(1)
            assertEquals(1, loads.get())
            cache.popular(2)
            assertEquals(2, loads.get())
            clock += Duration.ofMinutes(6).toNanos()
            cache.popular(1)
            assertEquals(3, loads.get())
        }

    @Test
    fun `concurrent identical calls share one load`() =
        runBlocking {
            val cache = cache()
            val results =
                (1..5)
                    .map {
                        async {
                            cache.get(1, "popular", ResultCache.Kind.LIST, request(1)) {
                                loads.incrementAndGet()
                                delay(100)
                                popularResponse { series += series { key = "slow" } }
                            }
                        }
                    }.awaitAll()
            assertEquals(1, loads.get())
            assertEquals(5, results.count { it.seriesList.single().key == "slow" })
        }

    @Test
    fun `errors aren't cached, bypass refreshes, invalidation drops a source`() =
        runBlocking {
            val cache = cache()
            assertFailsWith<IllegalStateException> {
                cache.get(1, "popular", ResultCache.Kind.LIST, request(1)) { error("boom") }
            }
            cache.popular(1)
            assertEquals(1, loads.get())

            val bypass = Context.current().withValue(ResultCache.BYPASS, true)
            val previous = bypass.attach()
            try {
                cache.popular(1)
            } finally {
                bypass.detach(previous)
            }
            assertEquals(2, loads.get())

            cache.popular(1, sourceId = 2)
            cache.invalidate(1)
            cache.popular(1)
            cache.popular(1, sourceId = 2)
            assertEquals(4, loads.get())
        }

    @Test
    fun `size is bounded`() =
        runBlocking {
            val cache = cache(maxBytes = 100)
            (1..20).forEach { cache.popular(it) }
            cache.popular(1)
            assertEquals(21, loads.get())
        }

    @Test
    fun `kinds without a lifetime aren't cached`() =
        runBlocking {
            val cache = cache()
            repeat(2) {
                cache.get(1, "pages", ResultCache.Kind.PAGES, request(1)) {
                    loads.incrementAndGet()
                    popularResponse {}
                }
            }
            assertEquals(2, loads.get())
        }
}
