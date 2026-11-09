// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.testext.lib16

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.ResolvableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.UriType
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/** Two sources, one per language, like multi-language extensions. */
class TestFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(TestSource("en"), TestSource("fr"))
}

/**
 * A lib 1.6-style source over a small JSON API, used by mihon-source's tests. It uses the
 * suspend API, every filter type, preferences, Injekt, per-source headers and a URL
 * resolver.
 */
class TestSource(
    override val lang: String,
) : HttpSource(),
    ConfigurableSource,
    ResolvableSource {
    override val name = "Lib16 Test"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL, null) ?: System.getProperty("panelarr.testext.baseUrl", "http://127.0.0.1:1")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("X-Test-Lang", lang)

    override suspend fun getPopularManga(page: Int): MangasPage = list("$baseUrl/api/popular?page=$page&lang=$lang")

    override suspend fun getLatestUpdates(page: Int): MangasPage = list("$baseUrl/api/latest?page=$page&lang=$lang")

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val url =
            "$baseUrl/api/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("q", query)
        filters.forEach { filter ->
            when (filter) {
                is AuthorFilter -> {
                    if (filter.state.isNotBlank()) url.addQueryParameter("author", filter.state)
                }

                is AdultFilter -> {
                    if (filter.state) url.addQueryParameter("adult", "1")
                }

                is StatusFilter -> {
                    url.addQueryParameter("status", STATUSES[filter.state])
                }

                is SortFilter -> {
                    filter.state?.let { url.addQueryParameter("sort", SORTS[it.index] + if (it.ascending) "+" else "-") }
                }

                is GenreGroup -> {
                    filter.state.forEach { genre ->
                        when {
                            genre.isIncluded() -> url.addQueryParameter("genre", genre.name)
                            genre.isExcluded() -> url.addQueryParameter("exclude", genre.name)
                        }
                    }
                }

                else -> {}
            }
        }
        if (preferences.getBoolean(PREF_ADULT, false)) url.addQueryParameter("pref_adult", "1")
        return list(url.build().toString())
    }

    override fun getFilterList(): FilterList =
        FilterList(
            Filter.Header("Test filters"),
            Filter.Separator(),
            AuthorFilter(),
            AdultFilter(),
            GenreGroup(listOf(Genre("Action"), Genre("Comedy"), Genre("Drama"))),
            StatusFilter(),
            SortFilter(),
        )

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details =
            if (fetchDetails) {
                val dto = get<SeriesDto>("$baseUrl/api/series/${manga.url}")
                SManga.create().apply {
                    url = dto.id
                    title = dto.title
                    author = dto.author
                    artist = dto.artist
                    description = dto.description
                    genre = dto.genres.joinToString()
                    status =
                        when (dto.status) {
                            "ongoing" -> SManga.ONGOING
                            "completed" -> SManga.COMPLETED
                            else -> SManga.UNKNOWN
                        }
                    thumbnail_url = dto.cover?.let { baseUrl + it }
                    initialized = true
                }
            } else {
                manga
            }
        val chapterList =
            if (fetchChapters) {
                get<List<ChapterDto>>("$baseUrl/api/series/${manga.url}/chapters").map { dto ->
                    SChapter.create().apply {
                        url = dto.id
                        name = dto.name
                        chapter_number = dto.number
                        // ZZZZZ is an ISO offset on Android (ICU); the JDK can't parse this.
                        date_upload = dto.date?.let { DATE_FORMAT.parse(it)?.time } ?: dto.uploaded
                        scanlator = dto.scanlator
                    }
                }
            } else {
                chapters
            }
        return SMangaUpdate(details, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        get<List<PageDto>>("$baseUrl/api/chapters/${chapter.url}/pages").map { dto ->
            Page(dto.index, url = dto.page?.let { baseUrl + it } ?: "", imageUrl = dto.image?.let { baseUrl + it })
        }

    override suspend fun getImageUrl(page: Page): String = baseUrl + get<ImageDto>(page.url).image

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    /** Claims every URL on its site, like many resolvers do. */
    override fun getUriType(uri: String): UriType = if (uri.startsWith(baseUrl)) UriType.Manga else UriType.Unknown

    /**
     * Resolves short links (`<base>/r/<id>`). Series pages (`<base>/series/<id>`) make it
     * fail, standing in for a resolver broken by a site change; anything else is unknown.
     */
    override suspend fun getManga(uri: String): SManga? {
        val path = uri.removePrefix(baseUrl)
        return when {
            path.startsWith("/r/") -> {
                val id = path.removePrefix("/r/").trimEnd('/')
                SManga.create().apply {
                    url = id
                    title = get<SeriesDto>("$baseUrl/api/series/$id").title
                }
            }

            path.startsWith("/series/") -> {
                throw IllegalStateException("the test resolver fails on series pages")
            }

            else -> {
                null
            }
        }
    }

    override suspend fun getChapter(uri: String): SChapter? = null

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = PREF_BASE_URL
                title = "Base URL"
                summary = "Address of the test server"
                setDefaultValue("")
                dialogTitle = "Base URL"
            },
        )
        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_ADULT
                title = "Show adult titles"
                setDefaultValue(false)
            },
        )
        screen.addPreference(
            ListPreference(screen.context).apply {
                key = PREF_QUALITY
                title = "Image quality"
                entries = arrayOf("High", "Low")
                entryValues = arrayOf("high", "low")
                setDefaultValue("high")
                summary = "%s"
            },
        )
        screen.addPreference(
            MultiSelectListPreference(screen.context).apply {
                key = PREF_LANGS
                title = "Languages"
                entries = arrayOf("English", "French")
                entryValues = arrayOf("en", "fr")
                setDefaultValue(setOf("en"))
            },
        )
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = PREF_REJECTED
                title = "Rejects 'bad'"
                setDefaultValue("")
                setOnPreferenceChangeListener { _, value -> value != "bad" }
            },
        )
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = PREF_TOKEN
                title = "API token"
                // A secret, as extensions mark them: a password input.
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            },
        )
    }

    private suspend fun list(url: String): MangasPage {
        val dto = get<ListDto>(url)
        val mangas =
            dto.items.map { item ->
                SManga.create().apply {
                    this.url = item.id
                    title = item.title
                    thumbnail_url = item.cover?.let { baseUrl + it }
                }
            }
        return MangasPage(mangas, dto.hasNext)
    }

    private suspend inline fun <reified T> get(url: String): T {
        val response: Response = client.newCall(GET(url, headers)).awaitSuccess()
        return response.use { json.decodeFromString<T>(it.body.string()) }
    }

    private class AuthorFilter : Filter.Text("Author")

    private class AdultFilter : Filter.CheckBox("Adult only")

    private class Genre(
        name: String,
    ) : Filter.TriState(name)

    private class GenreGroup(
        genres: List<Genre>,
    ) : Filter.Group<Genre>("Genres", genres)

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("Any", "Ongoing", "Completed"))

    private class SortFilter : Filter.Sort("Sort", arrayOf("Popular", "Latest"), Selection(0, false))

    @Serializable
    private class ListDto(
        val items: List<ItemDto>,
        val hasNext: Boolean,
    )

    @Serializable
    private class ItemDto(
        val id: String,
        val title: String,
        val cover: String? = null,
    )

    @Serializable
    private class SeriesDto(
        val id: String,
        val title: String,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val status: String? = null,
        val cover: String? = null,
    )

    @Serializable
    private class ChapterDto(
        val id: String,
        val name: String,
        val number: Float = -1f,
        val uploaded: Long = 0,
        val date: String? = null,
        val scanlator: String? = null,
    )

    @Serializable
    private class PageDto(
        val index: Int,
        val image: String? = null,
        val page: String? = null,
    )

    @Serializable
    private class ImageDto(
        val image: String,
    )

    companion object {
        const val PREF_BASE_URL = "base_url"
        const val PREF_ADULT = "show_adult"
        const val PREF_QUALITY = "quality"
        const val PREF_LANGS = "langs"
        const val PREF_REJECTED = "rejects_bad"
        const val PREF_TOKEN = "api_token"
        private val STATUSES = listOf("any", "ongoing", "completed")
        private val DATE_FORMAT = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ", java.util.Locale.US)
        private val SORTS = listOf("popular", "latest")
    }
}
