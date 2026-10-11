// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

import com.google.protobuf.ByteString
import eu.kanade.tachiyomi.source.ResolvableSource
import eu.kanade.tachiyomi.source.UriType
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import panelarr.mihonsource.BuildInfo
import panelarr.mihonsource.SERVICE_NAME
import panelarr.mihonsource.mihon.FilterMapping
import panelarr.mihonsource.mihon.Mapping
import panelarr.mihonsource.mihon.MihonSource
import panelarr.mihonsource.mihon.MihonSources
import panelarr.mihonsource.mihon.ResultCache
import panelarr.mihonsource.mihon.SourceErrors
import panelarr.mihonsource.mihon.SourceSettings
import panelarr.mihonsource.mihon.UrlMatch
import panelarr.mihonsource.network.PushedProfiles
import panelarr.source.v1.CommonService
import panelarr.source.v1.FetchImageRequest
import panelarr.source.v1.FetchImageResponse
import panelarr.source.v1.GetFiltersRequest
import panelarr.source.v1.GetFiltersResponse
import panelarr.source.v1.GetInfoRequest
import panelarr.source.v1.GetInfoResponse
import panelarr.source.v1.GetSeriesRequest
import panelarr.source.v1.GetSeriesResponse
import panelarr.source.v1.GetSettingsRequest
import panelarr.source.v1.GetSettingsResponse
import panelarr.source.v1.GetSettingsSchemaRequest
import panelarr.source.v1.GetSettingsSchemaResponse
import panelarr.source.v1.GetSourceRequest
import panelarr.source.v1.GetSourceResponse
import panelarr.source.v1.HealthRequest
import panelarr.source.v1.HealthResponse
import panelarr.source.v1.HealthStatus
import panelarr.source.v1.LatestRequest
import panelarr.source.v1.LatestResponse
import panelarr.source.v1.ListChaptersRequest
import panelarr.source.v1.ListChaptersResponse
import panelarr.source.v1.ListPagesRequest
import panelarr.source.v1.ListPagesResponse
import panelarr.source.v1.ListSourcesRequest
import panelarr.source.v1.ListSourcesResponse
import panelarr.source.v1.PluginCapability
import panelarr.source.v1.PopularRequest
import panelarr.source.v1.PopularResponse
import panelarr.source.v1.ResolveUrlRequest
import panelarr.source.v1.ResolveUrlResponse
import panelarr.source.v1.SearchRequest
import panelarr.source.v1.SearchResponse
import panelarr.source.v1.Series
import panelarr.source.v1.SetNetworkProfileRequest
import panelarr.source.v1.SetNetworkProfileResponse
import panelarr.source.v1.SetSettingsRequest
import panelarr.source.v1.SetSettingsResponse
import panelarr.source.v1.SourcePluginServiceGrpcKt
import panelarr.source.v1.fetchImageResponse
import panelarr.source.v1.getFiltersResponse
import panelarr.source.v1.getInfoResponse
import panelarr.source.v1.getSeriesResponse
import panelarr.source.v1.getSettingsResponse
import panelarr.source.v1.getSettingsSchemaResponse
import panelarr.source.v1.getSourceResponse
import panelarr.source.v1.healthResponse
import panelarr.source.v1.imageMetadata
import panelarr.source.v1.latestResponse
import panelarr.source.v1.listChaptersResponse
import panelarr.source.v1.listPagesResponse
import panelarr.source.v1.listSourcesResponse
import panelarr.source.v1.pluginInfo
import panelarr.source.v1.popularResponse
import panelarr.source.v1.resolveUrlResponse
import panelarr.source.v1.searchResponse
import panelarr.source.v1.setNetworkProfileResponse
import panelarr.source.v1.setSettingsResponse

/** `panelarr.source.v1`: the source API, backed by the loaded Mihon sources. */
@Suppress("TooManyFunctions")
class SourcePluginApi(
    private val sources: MihonSources,
    private val pushed: PushedProfiles,
    private val cache: ResultCache = ResultCache(emptyMap(), 0),
    /** Number of installed extensions that failed to load, for Health. */
    private val failedExtensions: () -> Int = { 0 },
) : SourcePluginServiceGrpcKt.SourcePluginServiceCoroutineImplBase() {
    override suspend fun getInfo(request: GetInfoRequest): GetInfoResponse =
        getInfoResponse {
            info =
                pluginInfo {
                    id = SERVICE_NAME
                    name = SERVICE_NAME
                    version = BuildInfo.version
                    contracts += listOf(CONTRACT_SOURCE, CONTRACT_MIHON)
                    capabilities +=
                        listOf(PluginCapability.PLUGIN_CAPABILITY_DYNAMIC_SOURCES, PluginCapability.PLUGIN_CAPABILITY_NETWORK_PROFILES)
                    commonServices += listOf(CommonService.COMMON_SERVICE_NETWORK_PROFILE, CommonService.COMMON_SERVICE_SOLVER)
                }
        }

    override suspend fun health(request: HealthRequest): HealthResponse {
        val failed = failedExtensions()
        return healthResponse {
            status = if (failed == 0) HealthStatus.HEALTH_STATUS_SERVING else HealthStatus.HEALTH_STATUS_DEGRADED
            if (failed > 0) message = "$failed extension(s) failed to load"
        }
    }

    override suspend fun listSources(request: ListSourcesRequest): ListSourcesResponse =
        rpc {
            listSourcesResponse {
                sources +=
                    this@SourcePluginApi.sources.all().filter { request.lang.isEmpty() || it.lang == request.lang }.map(
                        this@SourcePluginApi.sources::toProto,
                    )
            }
        }

    override suspend fun getSource(request: GetSourceRequest): GetSourceResponse =
        rpc { getSourceResponse { source = sources.toProto(sources.get(request.sourceId)) } }

    override suspend fun getFilters(request: GetFiltersRequest): GetFiltersResponse =
        rpc {
            val source = sources.get(request.sourceId)
            cache.get(source.id, "filters", ResultCache.Kind.FILTERS, request) {
                val list = sources.call(source, "filters") { it.source.getFilterList() }
                getFiltersResponse { filters += FilterMapping.filters(list) }
            }
        }

    override suspend fun popular(request: PopularRequest): PopularResponse =
        rpc {
            val source = sources.get(request.sourceId)
            cache.get(source.id, "popular", ResultCache.Kind.LIST, request) {
                val page = sources.call(source, "popular") { it.source.getPopularManga(pageNumber(request.page)) }
                popularResponse {
                    series += seriesList(page)
                    hasNextPage = page.hasNextPage
                }
            }
        }

    override suspend fun latest(request: LatestRequest): LatestResponse =
        rpc {
            val source = sources.get(request.sourceId)
            if (!source.supportsLatest) throw SourceErrors.unsupported("this source has no latest list")
            cache.get(source.id, "latest", ResultCache.Kind.LIST, request) {
                val page = sources.call(source, "latest") { it.source.getLatestUpdates(pageNumber(request.page)) }
                latestResponse {
                    series += seriesList(page)
                    hasNextPage = page.hasNextPage
                }
            }
        }

    override suspend fun search(request: SearchRequest): SearchResponse =
        rpc {
            val source = sources.get(request.sourceId)
            cache.get(source.id, "search", ResultCache.Kind.LIST, request) {
                val page =
                    sources.call(source, "search") {
                        val filters = FilterMapping.applyFilters(it.source.getFilterList(), request.filtersList)
                        it.source.getSearchManga(pageNumber(request.page), request.query, filters)
                    }
                searchResponse {
                    series += seriesList(page)
                    hasNextPage = page.hasNextPage
                }
            }
        }

    override suspend fun resolveUrl(request: ResolveUrlRequest): ResolveUrlResponse =
        rpc {
            val url = request.url.trim()
            val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("url must be an absolute http(s) URL")
            val candidates =
                if (request.sourceId.isNotEmpty()) {
                    listOf(sources.get(request.sourceId))
                } else {
                    sources.all().filter { it.resolvable || UrlMatch.sameSite(it.homeUrl, parsed) }
                }
            for (source in candidates) {
                val resolved =
                    if (source.resolvable) native(source, url) else bySearch(source, url)
                if (resolved != null) return@rpc resolved
            }
            throw SourceErrors.notFound("no source recognizes this URL")
        }

    /** The source's own resolver. */
    private suspend fun native(
        source: MihonSource,
        url: String,
    ): ResolveUrlResponse? {
        val resolvable = source.source as ResolvableSource
        val manga =
            sources.call(source, "resolve_url") { if (resolvable.getUriType(url) == UriType.Manga) resolvable.getManga(url) else null }
                ?: return null
        return resolveUrlResponse {
            sourceId = source.id.toString()
            series = Mapping.withUrl(Mapping.series(manga), webUrl(source) { http -> http.getMangaUrl(manga) })
        }
    }

    /**
     * Best effort for sources without a resolver: many extensions accept a pasted URL as a
     * search query. A result counts when its web URL is the given one, or when it's the
     * only result.
     */
    private suspend fun bySearch(
        source: MihonSource,
        url: String,
    ): ResolveUrlResponse? {
        val page =
            runCatching { sources.call(source, "resolve_url") { it.source.getSearchManga(1, url, it.source.getFilterList()) } }
                .getOrNull() ?: return null
        val match =
            page.mangas.firstOrNull { manga -> webUrl(source) { http -> http.getMangaUrl(manga) }?.let { UrlMatch.same(it, url) } == true }
                ?: page.mangas.singleOrNull()
                ?: return null
        return resolveUrlResponse {
            sourceId = source.id.toString()
            series = Mapping.withUrl(Mapping.series(match), webUrl(source) { http -> http.getMangaUrl(match) })
            bestEffort = true
        }
    }

    override suspend fun getSeries(request: GetSeriesRequest): GetSeriesResponse =
        rpc {
            require(request.key.isNotEmpty()) { "key is required" }
            val source = sources.get(request.sourceId)
            cache.get(source.id, "series", ResultCache.Kind.DETAILS, request) {
                val update =
                    sources.call(
                        source,
                        "series",
                    ) { it.source.getMangaUpdate(Mapping.mangaFor(request.key), emptyList(), true, false) }
                val manga = update.manga
                getSeriesResponse {
                    series =
                        Mapping.withUrl(
                            Mapping.series(manga, key = request.key, detailsLoaded = true),
                            webUrl(source) { http -> http.getMangaUrl(Mapping.mangaFor(request.key)) },
                        )
                }
            }
        }

    override suspend fun listChapters(request: ListChaptersRequest): ListChaptersResponse =
        rpc {
            require(request.seriesKey.isNotEmpty()) { "series_key is required" }
            val source = sources.get(request.sourceId)
            cache.get(source.id, "chapters", ResultCache.Kind.DETAILS, request) {
                val update =
                    sources.call(
                        source,
                        "chapters",
                    ) { it.source.getMangaUpdate(Mapping.mangaFor(request.seriesKey), emptyList(), false, true) }
                listChaptersResponse {
                    chapters +=
                        update.chapters.map { chapter -> Mapping.chapter(chapter, webUrl(source) { http -> http.getChapterUrl(chapter) }) }
                }
            }
        }

    override suspend fun listPages(request: ListPagesRequest): ListPagesResponse =
        rpc {
            require(request.chapterKey.isNotEmpty()) { "chapter_key is required" }
            val source = sources.get(request.sourceId)
            cache.get(source.id, "pages", ResultCache.Kind.PAGES, request) {
                val pages = sources.call(source, "pages") { it.source.getPageList(Mapping.chapterFor(request.chapterKey)) }
                listPagesResponse { this.pages += pages.map(Mapping::page) }
            }
        }

    override fun fetchImage(request: FetchImageRequest): Flow<FetchImageResponse> =
        flow {
            val source = rpc { sources.get(request.sourceId) }
            require(request.ref.isNotEmpty()) { "ref is required" }
            sources.openImage(source, request.ref).use { response ->
                val body = response.body
                emit(
                    fetchImageResponse {
                        metadata =
                            imageMetadata {
                                contentType = body.contentType()?.toString() ?: "application/octet-stream"
                                contentLength = body.contentLength().coerceAtLeast(0)
                            }
                    },
                )
                body.byteStream().use { input ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        emit(fetchImageResponse { chunk = ByteString.copyFrom(buffer, 0, read) })
                    }
                }
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun getSettingsSchema(request: GetSettingsSchemaRequest): GetSettingsSchemaResponse =
        rpc {
            val source = configurable(request.sourceId)
            getSettingsSchemaResponse { schema = sources.call(source, "settings_schema") { SourceSettings(it).schema() } }
        }

    override suspend fun getSettings(request: GetSettingsRequest): GetSettingsResponse =
        rpc {
            val source = configurable(request.sourceId)
            getSettingsResponse { values = sources.call(source, "settings") { SourceSettings(it).values() } }
        }

    override suspend fun setSettings(request: SetSettingsRequest): SetSettingsResponse =
        rpc {
            val source = configurable(request.sourceId)
            val values = sources.call(source, "set_settings") { SourceSettings(it).set(request.values) }
            // Results may depend on settings (e.g. a mirror or content preference).
            cache.invalidate(source.id)
            setSettingsResponse { this.values = values }
        }

    override suspend fun setNetworkProfile(request: SetNetworkProfileRequest): SetNetworkProfileResponse =
        rpc {
            val source = sources.get(request.sourceId)
            pushed.push(source.id, if (request.hasProfile()) request.profile else null)
            cache.invalidate(source.id)
            setNetworkProfileResponse {}
        }

    private fun configurable(sourceId: String): MihonSource {
        val source = sources.get(sourceId)
        if (!source.configurable) throw SourceErrors.unsupported("this source has no settings")
        return source
    }

    private fun seriesList(page: MangasPage): List<Series> = page.mangas.map { manga -> Mapping.series(manga, detailsLoaded = false) }

    private fun webUrl(
        source: MihonSource,
        block: (HttpSource) -> String,
    ): String? = (source.source as? HttpSource)?.let { http -> runCatching { block(http) }.getOrNull() }

    private fun pageNumber(page: Int): Int = if (page < 1) 1 else page

    companion object {
        const val CONTRACT_SOURCE = "panelarr.source.v1"
        const val CONTRACT_MIHON = "panelarr.mihon.v1"
        private const val CHUNK_SIZE = 64 * 1024
    }
}
