// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import rx.Observable

/** A source of series. The shape follows extensions-lib 1.6; older members are kept for 1.4 extensions. */
interface Source {
    val id: Long

    val name: String

    val supportsLatest: Boolean

    fun getFilterList(): FilterList = FilterList()

    suspend fun getPopularManga(page: Int): MangasPage

    suspend fun getLatestUpdates(page: Int): MangasPage

    suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage

    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate

    suspend fun getPageList(chapter: SChapter): List<Page>

    @Deprecated("Use getMangaUpdate")
    fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw UnsupportedOperationException("Unsupported!")

    @Deprecated("Use getMangaUpdate")
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw UnsupportedOperationException("Unsupported!")

    @Deprecated("Use getPageList")
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> = throw UnsupportedOperationException("Unsupported!")
}

/** A source with a language and browsable catalogue. Its suspend members bridge to the older Rx ones. */
interface CatalogueSource : Source {
    val lang: String

    override suspend fun getPopularManga(page: Int): MangasPage = fetchPopularManga(page).awaitSingle()

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchLatestUpdates(page).awaitSingle()

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = fetchSearchManga(page, query, filters).awaitSingle()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate =
        kotlinx.coroutines.supervisorScope {
            val details = if (fetchDetails) async { fetchMangaDetails(manga).awaitSingle() } else null
            val list = if (fetchChapters) async { fetchChapterList(manga).awaitSingle() } else null
            SMangaUpdate(details?.await() ?: manga, list?.await() ?: chapters)
        }

    override suspend fun getPageList(chapter: SChapter): List<Page> = fetchPageList(chapter).awaitSingle()

    @Deprecated("Use getPopularManga")
    fun fetchPopularManga(page: Int): Observable<MangasPage> = throw UnsupportedOperationException("Unsupported!")

    @Deprecated("Use getSearchManga")
    fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> = throw UnsupportedOperationException("Unsupported!")

    @Deprecated("Use getLatestUpdates")
    fun fetchLatestUpdates(page: Int): Observable<MangasPage> = throw UnsupportedOperationException("Unsupported!")
}

/** A source with user settings, shown from its preference screen. */
interface ConfigurableSource {
    fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen)
}

/** Creates several sources, e.g. one per language. */
interface SourceFactory {
    fun createSources(): List<Source>
}

/** A source whose images aren't limited (no download throttling needed). */
interface UnmeteredSource

/** A source that can turn a web URL into a series or chapter. */
interface ResolvableSource : Source {
    fun getUriType(uri: String): UriType

    suspend fun getManga(uri: String): SManga?

    suspend fun getChapter(uri: String): SChapter?
}

sealed interface UriType {
    data object Manga : UriType

    data object Chapter : UriType

    data object Unknown : UriType
}
