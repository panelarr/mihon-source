// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import com.google.protobuf.Timestamp
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaImpl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import panelarr.source.v1.Chapter
import panelarr.source.v1.FilterValue
import panelarr.source.v1.Series
import panelarr.source.v1.SeriesStatus
import panelarr.source.v1.TriState
import panelarr.source.v1.chapter
import panelarr.source.v1.checkboxFilter
import panelarr.source.v1.filter
import panelarr.source.v1.groupFilter
import panelarr.source.v1.headerFilter
import panelarr.source.v1.page
import panelarr.source.v1.selectFilter
import panelarr.source.v1.separatorFilter
import panelarr.source.v1.series
import panelarr.source.v1.sortFilter
import panelarr.source.v1.sortSelection
import panelarr.source.v1.textFilter
import panelarr.source.v1.triStateFilter
import java.util.Base64
import panelarr.source.v1.Filter as ProtoFilter
import panelarr.source.v1.Page as ProtoPage

/** Converts between Mihon's model and `panelarr.source.v1`. */
object Mapping {
    /**
     * The IDs a source is known by in other apps (`Source.compat_ids`). A Mihon source ID is
     * the same number in Mihon and its forks, and their backups name sources by it, so hosts
     * can match imported data with `mihon:<id>`.
     */
    fun compatIds(sourceId: Long): List<String> = listOf("$MIHON_SCHEME:$sourceId")

    fun series(
        manga: SManga,
        key: String? = null,
        detailsLoaded: Boolean = manga.initialized,
    ): Series =
        series {
            this.key = key ?: manga.urlOrEmpty()
            title = manga.titleOrEmpty()
            manga.author?.let { authors += splitPeople(it) }
            manga.artist?.let { artists += splitPeople(it) }
            manga.description?.let { description = it }
            manga.getGenres()?.let { genres += it }
            status = status(manga.status)
            manga.thumbnail_url?.let { coverUrl = it }
            this.detailsLoaded = detailsLoaded
        }

    fun withUrl(
        series: Series,
        url: String?,
    ): Series = if (url == null) series else series.toBuilder().setUrl(url).build()

    fun chapter(
        chapter: SChapter,
        url: String?,
    ): Chapter =
        chapter {
            key = chapter.urlOrEmpty()
            name = chapter.nameOrEmpty()
            if (chapter.chapter_number >= 0) number = chapter.chapter_number.toDouble()
            chapter.scanlator?.let { scanlator = it }
            if (chapter.date_upload > 0) uploadedAt = timestamp(chapter.date_upload)
            url?.let { this.url = it }
        }

    fun mangaFor(key: String): SManga =
        SManga.create().apply {
            url = key
            title = ""
        }

    fun chapterFor(key: String): SChapter =
        SChapter.create().apply {
            url = key
            name = ""
        }

    private fun status(value: Int): SeriesStatus =
        when (value) {
            SManga.ONGOING -> SeriesStatus.SERIES_STATUS_ONGOING
            SManga.COMPLETED -> SeriesStatus.SERIES_STATUS_COMPLETED
            SManga.LICENSED -> SeriesStatus.SERIES_STATUS_LICENSED
            SManga.PUBLISHING_FINISHED -> SeriesStatus.SERIES_STATUS_PUBLISHING_FINISHED
            SManga.CANCELLED -> SeriesStatus.SERIES_STATUS_CANCELLED
            SManga.ON_HIATUS -> SeriesStatus.SERIES_STATUS_ON_HIATUS
            else -> SeriesStatus.SERIES_STATUS_UNSPECIFIED
        }

    private fun splitPeople(value: String): List<String> =
        value
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun timestamp(millis: Long): Timestamp =
        Timestamp
            .newBuilder()
            .setSeconds(Math.floorDiv(millis, MILLIS))
            .setNanos((Math.floorMod(millis, MILLIS) * NANOS_PER_MILLI).toInt())
            .build()

    private fun SManga.urlOrEmpty(): String = if (this is SMangaImpl && !hasUrl()) "" else url

    private fun SManga.titleOrEmpty(): String = if (this is SMangaImpl && !hasTitle()) "" else title

    private fun SChapter.urlOrEmpty(): String = if (this is SChapterImpl && !hasUrl()) "" else url

    private fun SChapter.nameOrEmpty(): String = if (this is SChapterImpl && !hasName()) "" else name

    // ---- Pages ----

    /** What a page ref carries, so FetchImage can rebuild the page without asking the source again. */
    @Serializable
    data class PageRef(
        val i: Int,
        val u: String = "",
        val iu: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun page(page: Page): ProtoPage =
        page {
            index = page.index
            ref = encodeRef(PageRef(page.index, page.url, page.imageUrl))
            page.imageUrl?.let { imageUrl = it }
        }

    fun encodeRef(ref: PageRef): String =
        REF_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(PageRef.serializer(), ref).toByteArray())

    /** A page ref, or null when [ref] isn't one (then it's a plain image URL). */
    fun decodeRef(ref: String): PageRef? {
        if (!ref.startsWith(REF_PREFIX)) return null
        val text = runCatching { String(Base64.getUrlDecoder().decode(ref.removePrefix(REF_PREFIX))) }.getOrNull()
        return text?.let { runCatching { json.decodeFromString(PageRef.serializer(), it) }.getOrNull() }
            ?: throw IllegalArgumentException("invalid page ref")
    }

    private const val MIHON_SCHEME = "mihon"
    private const val REF_PREFIX = "p1."
    private const val MILLIS = 1000L
    private const val NANOS_PER_MILLI = 1_000_000L
}
