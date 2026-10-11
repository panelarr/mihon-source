// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.awaitSingle
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import panelarr.mihonsource.extensionapi.ExtensionHost
import rx.Observable
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * Base class of sources backed by a website. Member shapes (open/final, defaults, the lazy
 * delegates) follow extensions-lib so that compiled extensions link against it.
 */
@Suppress("TooManyFunctions")
abstract class HttpSource : CatalogueSource {
    /** This source's own network helper: its client follows the source's network profile. */
    protected val network: NetworkHelper by lazy { ExtensionHost.networkFor(this) }

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    /** Bumped by an extension when its URLs change so much that the id must change too. */
    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    // Must stay a lazy delegate: some extensions replace the "headers$delegate" field reflectively.
    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    protected fun generateId(
        name: String,
        lang: String,
        versionId: Int,
    ): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and BYTE_MASK shl BITS_PER_BYTE * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    protected open fun headersBuilder(): Headers.Builder =
        Headers.Builder().apply { add("User-Agent", network.defaultUserAgentProvider()) }

    override fun toString(): String = "$name (${lang.uppercase()})"

    // ---- Popular ----

    @Deprecated("Use the suspend API")
    override fun fetchPopularManga(page: Int): Observable<MangasPage> =
        client.newCall(popularMangaRequest(page)).asObservableSuccess().map { popularMangaParse(it) }

    @Deprecated("Use the suspend API")
    protected open fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    @Deprecated("Use the suspend API")
    protected open fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")

    // ---- Search ----

    @Deprecated("Use the suspend API")
    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> =
        Observable.defer {
            try {
                client.newCall(searchMangaRequest(page, query, filters)).asObservableSuccess()
            } catch (e: NoClassDefFoundError) {
                // RxJava doesn't handle errors, which tend to happen during global search.
                throw RuntimeException(e)
            }
        }.map { searchMangaParse(it) }

    @Deprecated("Use the suspend API")
    protected open fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = throw UnsupportedOperationException("Not used")

    @Deprecated("Use the suspend API")
    protected open fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")

    // ---- Latest ----

    @Deprecated("Use the suspend API")
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        client.newCall(latestUpdatesRequest(page)).asObservableSuccess().map { latestUpdatesParse(it) }

    @Deprecated("Use the suspend API")
    protected open fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    @Deprecated("Use the suspend API")
    protected open fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")

    // ---- Details ----

    @Deprecated("Use the suspend API")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        client.newCall(mangaDetailsRequest(manga)).asObservableSuccess().map { response ->
            mangaDetailsParse(response).apply { initialized = true }
        }

    @Deprecated("Use the suspend API")
    open fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    @Deprecated("Use the suspend API")
    protected open fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException("Not used")

    // ---- Chapters ----

    @Deprecated("Use the suspend API")
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        client.newCall(chapterListRequest(manga)).asObservableSuccess().map { chapterListParse(it) }

    @Deprecated("Use the suspend API")
    protected open fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    @Deprecated("Use the suspend API")
    protected open fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not used")

    // ---- Pages ----

    @Deprecated("Use the suspend API")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        client.newCall(pageListRequest(chapter)).asObservableSuccess().map { pageListParse(it) }

    @Deprecated("Use the suspend API")
    protected open fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    @Deprecated("Use the suspend API")
    protected open fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not used")

    // ---- Image URLs and images ----

    @Deprecated("Use getImageUrl")
    open fun fetchImageUrl(page: Page): Observable<String> =
        client.newCall(imageUrlRequest(page)).asObservableSuccess().map { imageUrlParse(it) }

    /** Called for pages whose [Page.imageUrl] isn't known yet. */
    open suspend fun getImageUrl(page: Page): String = fetchImageUrl(page).awaitSingle()

    @Deprecated("Use getImageUrl")
    protected open fun imageUrlRequest(page: Page): Request = GET(page.url, headers)

    @Deprecated("Use getImageUrl")
    protected open fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    protected open fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    /** Downloads a page's image through this source's client. Used by the host. */
    suspend fun getImage(page: Page): Response = client.newCall(imageRequest(page)).awaitSuccess()

    // ---- URLs ----

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String =
        try {
            val uri = URI(orig.replace(" ", "%20"))
            var out = uri.path
            if (uri.query != null) out += "?" + uri.query
            if (uri.fragment != null) out += "#" + uri.fragment
            out
        } catch (e: URISyntaxException) {
            orig
        }

    open fun getMangaUrl(manga: SManga): String = mangaDetailsRequest(manga).url.toString()

    open fun getChapterUrl(chapter: SChapter): String = pageListRequest(chapter).url.toString()

    @Deprecated("Not used")
    open fun prepareNewChapter(
        chapter: SChapter,
        manga: SManga,
    ) = Unit

    override fun getFilterList(): FilterList = FilterList()

    private companion object {
        const val BYTE_MASK = 0xffL
        const val BITS_PER_BYTE = 8
    }
}
