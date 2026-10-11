// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import panelarr.mihonsource.extension.ExtensionManager
import panelarr.mihonsource.extension.ExtensionRecord
import panelarr.source.v1.ContentRating
import panelarr.source.v1.SourceCapability
import panelarr.source.v1.source
import java.time.Duration
import panelarr.source.v1.Source as ProtoSource

/** Looks up loaded Mihon sources and runs calls into them with a timeout and error mapping. */
class MihonSources(
    private val extensions: ExtensionManager,
    private val callTimeout: Duration = Duration.ofMinutes(2),
) {
    fun all(): List<MihonSource> =
        extensions.sources().filterIsInstance<MihonSource>().sortedWith(
            compareBy({
                it.name.lowercase()
            }, { it.lang }),
        )

    fun get(id: String): MihonSource {
        val parsed = id.trim().toLongOrNull() ?: throw IllegalArgumentException("invalid source id '$id'")
        return all().firstOrNull { it.id == parsed } ?: throw SourceErrors.notFound("source '$id' not found")
    }

    /** Runs [block] against the source off the request thread, mapping failures to statuses. */
    suspend fun <T> call(
        source: MihonSource,
        block: suspend (MihonSource) -> T,
    ): T =
        try {
            withContext(Dispatchers.IO) { withTimeout(callTimeout.toMillis()) { block(source) } }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            if (e is VirtualMachineError && e !is OutOfMemoryError && e !is StackOverflowError) throw e
            throw SourceErrors.toStatus(e, source.id)
        }

    fun toProto(source: MihonSource): ProtoSource =
        source {
            id = source.id.toString()
            name = source.name
            lang = source.lang
            contentRating = rating(source.extension)
            source.homeUrl?.let { homeUrl = it }
            if (extensions.icons.has(source.extension.pkgName)) iconUrl = "/v1/sources/${source.id}/icon"
            capabilities += capabilities(source)
        }

    private fun capabilities(source: MihonSource): List<SourceCapability> =
        buildList {
            if (runCatching { source.supportsLatest }.getOrDefault(false)) add(SourceCapability.SOURCE_CAPABILITY_LATEST)
            if (source.source is CatalogueSource) add(SourceCapability.SOURCE_CAPABILITY_SEARCH)
            if (runCatching { source.source.getFilterList().isNotEmpty() }.getOrDefault(
                    false,
                )
            ) {
                add(SourceCapability.SOURCE_CAPABILITY_FILTERS)
            }
            if (source.resolvable) add(SourceCapability.SOURCE_CAPABILITY_RESOLVE_URL)
            if (source.configurable) add(SourceCapability.SOURCE_CAPABILITY_SETTINGS)
            if (source.needsWebView) add(SourceCapability.SOURCE_CAPABILITY_NEEDS_WEBVIEW)
            if (rating(source.extension) == ContentRating.CONTENT_RATING_NSFW) add(SourceCapability.SOURCE_CAPABILITY_NSFW)
        }

    private fun rating(record: ExtensionRecord): ContentRating =
        when (record.contentWarning) {
            ExtensionRecord.ContentWarning.SAFE -> ContentRating.CONTENT_RATING_SAFE
            ExtensionRecord.ContentWarning.MIXED -> ContentRating.CONTENT_RATING_MIXED
            ExtensionRecord.ContentWarning.NSFW -> ContentRating.CONTENT_RATING_NSFW
            null -> if (record.nsfw) ContentRating.CONTENT_RATING_NSFW else ContentRating.CONTENT_RATING_SAFE
        }

    /** Opens an image: a page ref through the source's own image request, or a plain URL with its headers. */
    suspend fun openImage(
        source: MihonSource,
        ref: String,
    ): Response =
        call(source) { mihon ->
            val http = mihon.source as? HttpSource ?: throw SourceErrors.unsupported("this source can't fetch images")
            val pageRef = Mapping.decodeRef(ref)
            if (pageRef == null) {
                val url = ref.trim()
                require(url.startsWith("http://") || url.startsWith("https://")) { "ref must be a page ref or an http(s) URL" }
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .headers(http.headers)
                        .build()
                val response = http.client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw eu.kanade.tachiyomi.network
                        .HttpException(response.code)
                }
                response
            } else {
                val page =
                    eu.kanade.tachiyomi.source.model
                        .Page(pageRef.i, pageRef.u, pageRef.iu)
                if (page.imageUrl == null) page.imageUrl = http.getImageUrl(page)
                http.getImage(page)
            }
        }
}
