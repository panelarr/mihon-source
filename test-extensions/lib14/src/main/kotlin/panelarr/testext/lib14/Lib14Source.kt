// SPDX-License-Identifier: GPL-3.0-or-later

// Lib 1.4 extensions override what lib 1.6 deprecated; that is the point of this fixture.
@file:Suppress("OVERRIDE_DEPRECATION")

package panelarr.testext.lib14

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import java.util.Properties

/**
 * A lib 1.4-style source over HTML pages, used by mihon-source's tests: it only implements
 * the request/parse methods, so everything goes through the host's Rx bridges.
 */
class Lib14Source : HttpSource() {
    override val name = "Lib14 Test"
    override val lang = "en"
    override val supportsLatest = false
    override val baseUrl: String
        get() = System.getProperty("panelarr.testext.baseUrl", "http://127.0.0.1:1")

    /** Proves assets are readable through the extension's classloader. */
    val greeting: String =
        Properties()
            .apply {
                Lib14Source::class.java.classLoader
                    .getResourceAsStream("assets/i18n.properties")
                    ?.use { load(it) }
            }.getProperty("greeting", "missing")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/html/popular?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas =
            document.select("li.series a").map { link ->
                SManga.create().apply {
                    setUrlWithoutDomain(link.attr("abs:href"))
                    title = link.text()
                    thumbnail_url = link.attr("data-cover").ifEmpty { null }
                }
            }
        return MangasPage(mangas, document.selectFirst("a.next") != null)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = GET("$baseUrl/html/search?q=$query&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")!!.text()
            author = document.selectFirst(".author")?.text()
            description = document.selectFirst(".description")?.text()
            genre = document.select(".genre").joinToString { it.text() }
            status = if (document.selectFirst(".completed") != null) SManga.COMPLETED else SManga.ONGOING
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> =
        response.asJsoup().select("li.chapter a").map { link ->
            SChapter.create().apply {
                setUrlWithoutDomain(link.attr("abs:href"))
                name = link.text()
                chapter_number = link.attr("data-number").toFloatOrNull() ?: -1f
            }
        }

    override fun pageListParse(response: Response): List<Page> =
        response.asJsoup().select("div.page").mapIndexed { index, div ->
            Page(index, url = div.attr("abs:data-page"))
        }

    override fun imageUrlParse(response: Response): String = response.asJsoup().selectFirst("img")!!.attr("abs:src")
}
