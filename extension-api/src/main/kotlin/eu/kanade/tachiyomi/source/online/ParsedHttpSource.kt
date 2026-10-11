// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** A source parsed from HTML with CSS selectors. */
@Deprecated("Use HttpSource")
@Suppress("TooManyFunctions")
abstract class ParsedHttpSource : HttpSource() {
    override fun popularMangaParse(response: Response): MangasPage = parseList(response.asJsoup(), popularMangaSelector(), popularMangaNextPageSelector(), ::popularMangaFromElement)

    protected abstract fun popularMangaSelector(): String

    protected abstract fun popularMangaFromElement(element: Element): SManga

    protected abstract fun popularMangaNextPageSelector(): String?

    override fun searchMangaParse(response: Response): MangasPage = parseList(response.asJsoup(), searchMangaSelector(), searchMangaNextPageSelector(), ::searchMangaFromElement)

    protected abstract fun searchMangaSelector(): String

    protected abstract fun searchMangaFromElement(element: Element): SManga

    protected abstract fun searchMangaNextPageSelector(): String?

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response.asJsoup(), latestUpdatesSelector(), latestUpdatesNextPageSelector(), ::latestUpdatesFromElement)

    protected abstract fun latestUpdatesSelector(): String

    protected abstract fun latestUpdatesFromElement(element: Element): SManga

    protected abstract fun latestUpdatesNextPageSelector(): String?

    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    protected abstract fun mangaDetailsParse(document: Document): SManga

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(chapterListSelector()).map { chapterFromElement(it) }
    }

    protected abstract fun chapterListSelector(): String

    protected abstract fun chapterFromElement(element: Element): SChapter

    override fun pageListParse(response: Response): List<Page> = pageListParse(response.asJsoup())

    protected abstract fun pageListParse(document: Document): List<Page>

    override fun imageUrlParse(response: Response): String = imageUrlParse(response.asJsoup())

    protected abstract fun imageUrlParse(document: Document): String

    private fun parseList(
        document: Document,
        selector: String,
        nextPageSelector: String?,
        fromElement: (Element) -> SManga,
    ): MangasPage {
        val mangas = document.select(selector).map(fromElement)
        val hasNextPage = nextPageSelector?.let { document.selectFirst(it) } != null
        return MangasPage(mangas, hasNextPage)
    }
}
