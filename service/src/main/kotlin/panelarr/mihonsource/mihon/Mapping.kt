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

    private fun splitPeople(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

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

    fun encodeRef(ref: PageRef): String = REF_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(PageRef.serializer(), ref).toByteArray())

    /** A page ref, or null when [ref] isn't one (then it's a plain image URL). */
    fun decodeRef(ref: String): PageRef? {
        if (!ref.startsWith(REF_PREFIX)) return null
        val text = runCatching { String(Base64.getUrlDecoder().decode(ref.removePrefix(REF_PREFIX))) }.getOrNull()
        return text?.let { runCatching { json.decodeFromString(PageRef.serializer(), it) }.getOrNull() }
            ?: throw IllegalArgumentException("invalid page ref")
    }

    // ---- Filters ----

    fun filters(list: FilterList): List<ProtoFilter> = list.map(::filter)

    private fun filter(source: Filter<*>): ProtoFilter =
        filter {
            name = source.name
            when (source) {
                is Filter.Header -> header = headerFilter {}
                is Filter.Separator -> separator = separatorFilter {}
                is Filter.Text -> text = textFilter { value = source.state }
                is Filter.CheckBox -> checkbox = checkboxFilter { value = source.state }
                is Filter.TriState -> triState = triStateFilter { value = triState(source.state) }
                is Filter.Select<*> ->
                    select =
                        selectFilter {
                            options += source.values.map { it.toString() }
                            selected = source.state.coerceAtLeast(0)
                        }
                is Filter.Sort ->
                    sort =
                        sortFilter {
                            options += source.values.toList()
                            source.state?.let { selection =
                                sortSelection {
                                    index = it.index
                                    ascending = it.ascending
                                }
                            }
                        }
                is Filter.Group<*> ->
                    group =
                        groupFilter {
                            filters += source.state.filterIsInstance<Filter<*>>().map(::filter)
                        }
            }
        }

    private fun triState(value: Int): TriState =
        when (value) {
            Filter.TriState.STATE_INCLUDE -> TriState.TRI_STATE_INCLUDE
            Filter.TriState.STATE_EXCLUDE -> TriState.TRI_STATE_EXCLUDE
            else -> TriState.TRI_STATE_IGNORE
        }

    /** Applies [values] to a fresh [list] by position. Mismatched paths or types are errors. */
    @Suppress("UNCHECKED_CAST")
    fun applyFilters(
        list: FilterList,
        values: List<FilterValue>,
    ): FilterList {
        values.forEach { value ->
            val path = value.pathList
            require(path.size in 1..2) { "filter path must have one or two elements" }
            val top = list.getOrNull(path[0]) ?: throw IllegalArgumentException("no filter at ${path.joinToString("/")}")
            val target =
                if (path.size == 2) {
                    val group = top as? Filter.Group<*> ?: throw IllegalArgumentException("filter ${path[0]} isn't a group")
                    group.state.getOrNull(path[1]) as? Filter<*> ?: throw IllegalArgumentException("no filter at ${path.joinToString("/")}")
                } else {
                    top
                }
            fun mismatch(): Nothing = throw IllegalArgumentException("filter '${target.name}' at ${path.joinToString("/")} doesn't take ${value.valueCase}")
            when (value.valueCase) {
                FilterValue.ValueCase.TEXT -> (target as? Filter.Text ?: mismatch()).state = value.text
                FilterValue.ValueCase.CHECKBOX -> (target as? Filter.CheckBox ?: mismatch()).state = value.checkbox
                FilterValue.ValueCase.TRI_STATE ->
                    (target as? Filter.TriState ?: mismatch()).state =
                        when (value.triState) {
                            TriState.TRI_STATE_INCLUDE -> Filter.TriState.STATE_INCLUDE
                            TriState.TRI_STATE_EXCLUDE -> Filter.TriState.STATE_EXCLUDE
                            else -> Filter.TriState.STATE_IGNORE
                        }
                FilterValue.ValueCase.SELECT -> {
                    val select = target as? Filter.Select<*> ?: mismatch()
                    require(value.select < select.values.size) { "option ${value.select} is out of range for '${select.name}'" }
                    select.state = value.select
                }
                FilterValue.ValueCase.SORT -> {
                    val sort = target as? Filter.Sort ?: mismatch()
                    require(value.sort.index < sort.values.size) { "option ${value.sort.index} is out of range for '${sort.name}'" }
                    sort.state = Filter.Sort.Selection(value.sort.index, value.sort.ascending)
                }
                else -> throw IllegalArgumentException("filter value at ${path.joinToString("/")} has no value")
            }
        }
        return list
    }

    private const val REF_PREFIX = "p1."
    private const val MILLIS = 1000L
    private const val NANOS_PER_MILLI = 1_000_000L
}
