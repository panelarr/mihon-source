// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.rpc.ErrorInfo
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.protobuf.StatusProto
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.mihonsource.fixtures.TestSigningKey
import panelarr.source.v1.FetchImageResponse
import panelarr.source.v1.Filter
import panelarr.source.v1.SeriesStatus
import panelarr.source.v1.SourceCapability
import panelarr.source.v1.TriState
import panelarr.source.v1.fetchImageRequest
import panelarr.source.v1.filterValue
import panelarr.source.v1.getFiltersRequest
import panelarr.source.v1.getSeriesRequest
import panelarr.source.v1.getSettingsRequest
import panelarr.source.v1.getSettingsSchemaRequest
import panelarr.source.v1.latestRequest
import panelarr.source.v1.listChaptersRequest
import panelarr.source.v1.listPagesRequest
import panelarr.source.v1.listSourcesRequest
import panelarr.source.v1.popularRequest
import panelarr.source.v1.searchRequest
import panelarr.source.v1.setSettingsRequest
import panelarr.source.v1.sortSelection
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The source API against the in-repo test extensions and a fake site. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceApiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var site: FakeSite
    private lateinit var service: TestService
    private lateinit var en: String
    private lateinit var lib14: String

    @BeforeAll
    fun start() {
        site = FakeSite()
        System.setProperty("panelarr.testext.baseUrl", site.baseUrl)
        service = TestService(dir.resolve("data"))
        service.install(TestExtensions.lib16)
        service.install(TestExtensions.lib14Apk(TestSigningKey(), dir.resolve("lib14.apk")))
        val sources = runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesList
        en = sources.single { it.name == "Lib16 Test" && it.lang == "en" }.id
        lib14 = sources.single { it.name == "Lib14 Test" }.id
    }

    @AfterAll
    fun stop() {
        service.close()
        site.close()
        System.clearProperty("panelarr.testext.baseUrl")
    }

    private val api get() = service.sources

    @Test
    fun `sources report capabilities and ratings`() {
        val sources = runBlocking { api.listSources(listSourcesRequest { lang = "en" }) }.sourcesList
        val source = sources.single { it.id == en }
        assertTrue(SourceCapability.SOURCE_CAPABILITY_LATEST in source.capabilitiesList)
        assertTrue(SourceCapability.SOURCE_CAPABILITY_FILTERS in source.capabilitiesList)
        assertTrue(SourceCapability.SOURCE_CAPABILITY_SETTINGS in source.capabilitiesList)
        assertEquals(site.baseUrl, source.homeUrl)
        val old = sources.single { it.id == lib14 }
        assertTrue(SourceCapability.SOURCE_CAPABILITY_NSFW in old.capabilitiesList)
        assertTrue(SourceCapability.SOURCE_CAPABILITY_LATEST !in old.capabilitiesList)
    }

    @Test
    fun `popular and latest pages`() {
        val popular = runBlocking { api.popular(popularRequest { sourceId = en; page = 1 }) }
        assertEquals(listOf("popular-en-1-1", "popular-en-1-2"), popular.seriesList.map { it.key })
        assertTrue(popular.hasNextPage)
        assertEquals(site.baseUrl + "/covers/a.png", popular.seriesList[0].coverUrl)
        val request = site.requestsTo("/api/popular").last()
        assertEquals("en", request.headers["X-Test-Lang"])

        val latest = runBlocking { api.latest(latestRequest { sourceId = en; page = 2 }) }
        assertEquals(false, latest.hasNextPage)
    }

    @Test
    fun `search applies filter values by position`() {
        val filters = runBlocking { api.getFilters(getFiltersRequest { sourceId = en }) }.filtersList
        assertEquals(
            listOf(Filter.KindCase.HEADER, Filter.KindCase.SEPARATOR, Filter.KindCase.TEXT, Filter.KindCase.CHECKBOX, Filter.KindCase.GROUP, Filter.KindCase.SELECT, Filter.KindCase.SORT),
            filters.map { it.kindCase },
        )
        assertEquals(3, filters[4].group.filtersCount)

        runBlocking {
            api.search(
                searchRequest {
                    sourceId = en
                    query = "hello"
                    this.filters +=
                        listOf(
                            filterValue { path += 2; text = "Someone" },
                            filterValue { path += 3; checkbox = true },
                            filterValue { path += listOf(4, 0); triState = TriState.TRI_STATE_INCLUDE },
                            filterValue { path += listOf(4, 2); triState = TriState.TRI_STATE_EXCLUDE },
                            filterValue { path += 5; select = 2 },
                            filterValue { path += 6; sort = sortSelection { index = 1; ascending = true } },
                        )
                },
            )
        }
        val url = site.requestsTo("/api/search").last().url
        assertEquals("hello", url.queryParameter("q"))
        assertEquals("Someone", url.queryParameter("author"))
        assertEquals("1", url.queryParameter("adult"))
        assertEquals("Action", url.queryParameter("genre"))
        assertEquals("Drama", url.queryParameter("exclude"))
        assertEquals("completed", url.queryParameter("status"))
        assertEquals("latest+", url.queryParameter("sort"))

        val bad = assertFailsWith<StatusException> { runBlocking { api.search(searchRequest { sourceId = en; this.filters += filterValue { path += 2; checkbox = true } }) } }
        assertEquals(Status.Code.INVALID_ARGUMENT, bad.status.code)
    }

    @Test
    fun `series details, chapters and pages`() {
        val series = runBlocking { api.getSeries(getSeriesRequest { sourceId = en; key = "s1" }) }.series
        assertEquals("s1", series.key)
        assertEquals("Series s1", series.title)
        assertEquals(listOf("Author One", "Author Two"), series.authorsList)
        assertEquals(listOf("Action", "Drama"), series.genresList)
        assertEquals(SeriesStatus.SERIES_STATUS_ONGOING, series.status)
        assertEquals(site.baseUrl + "/series/s1", series.url)
        assertTrue(series.detailsLoaded)

        val chapters = runBlocking { api.listChapters(listChaptersRequest { sourceId = en; seriesKey = "s1" }) }.chaptersList
        assertEquals(listOf("c2", "c1"), chapters.map { it.key })
        assertEquals(2.0, chapters[0].number)
        assertEquals(1_700_000_100L, chapters[0].uploadedAt.seconds)
        assertEquals("Group", chapters[0].scanlator)
        assertEquals(site.baseUrl + "/read/c2", chapters[0].url)

        val pages = runBlocking { api.listPages(listPagesRequest { sourceId = en; chapterKey = "c2" }) }.pagesList
        assertEquals(2, pages.size)
        assertEquals(site.baseUrl + "/images/0.png", pages[0].imageUrl)
        assertEquals("", pages[1].imageUrl)

        // The second page's image URL is resolved through the source when fetched.
        val parts = runBlocking { api.fetchImage(fetchImageRequest { sourceId = en; ref = pages[1].ref }).toList() }
        assertEquals(FetchImageResponse.PartCase.METADATA, parts.first().partCase)
        assertEquals("image/png", parts.first().metadata.contentType)
        val bytes = parts.drop(1).fold(ByteArray(0)) { acc, part -> acc + part.chunk.toByteArray() }
        assertContentEquals(FakeSite.IMAGE_BYTES + "/images/1.png".toByteArray(), bytes)

        val http = service.get("/v1/sources/$en/image?ref=" + java.net.URLEncoder.encode(pages[0].ref, Charsets.UTF_8))
        assertEquals(200, http.statusCode())
        assertEquals("image/png", http.headers().firstValue("content-type").get())
        assertContentEquals(FakeSite.IMAGE_BYTES + "/images/0.png".toByteArray(), http.body())
    }

    @Test
    fun `lib 1_4 sources work through the Rx bridges`() {
        val popular = runBlocking { api.popular(popularRequest { sourceId = lib14; page = 1 }) }
        assertEquals(listOf("/html/series/one", "/html/series/two"), popular.seriesList.map { it.key })
        val series = runBlocking { api.getSeries(getSeriesRequest { sourceId = lib14; key = "/html/series/one" }) }.series
        assertEquals("Html one", series.title)
        assertEquals(SeriesStatus.SERIES_STATUS_COMPLETED, series.status)
        val chapters = runBlocking { api.listChapters(listChaptersRequest { sourceId = lib14; seriesKey = "/html/series/one" }) }.chaptersList
        assertEquals("/html/read/1", chapters.single().key)
        val pages = runBlocking { api.listPages(listPagesRequest { sourceId = lib14; chapterKey = "/html/read/1" }) }.pagesList
        val image = runBlocking { api.fetchImage(fetchImageRequest { sourceId = lib14; ref = pages[1].ref }).toList() }
        assertContentEquals(FakeSite.IMAGE_BYTES + "/images/html-1.png".toByteArray(), image.drop(1).fold(ByteArray(0)) { a, p -> a + p.chunk.toByteArray() })
    }

    @Test
    fun `settings schema, values and changes`() {
        val schema = runBlocking { api.getSettingsSchema(getSettingsSchemaRequest { sourceId = en }) }.schema
        val properties = schema.fieldsMap.getValue("properties").structValue.fieldsMap
        assertEquals("string", properties.getValue("base_url").structValue.fieldsMap.getValue("type").stringValue)
        assertEquals("boolean", properties.getValue("show_adult").structValue.fieldsMap.getValue("type").stringValue)
        assertEquals(listOf("high", "low"), properties.getValue("quality").structValue.fieldsMap.getValue("enum").listValue.valuesList.map { it.stringValue })
        assertEquals("array", properties.getValue("langs").structValue.fieldsMap.getValue("type").stringValue)

        val defaults = runBlocking { api.getSettings(getSettingsRequest { sourceId = en }) }.values.fieldsMap
        assertEquals("high", defaults.getValue("quality").stringValue)
        assertEquals(false, defaults.getValue("show_adult").boolValue)

        val updated =
            runBlocking {
                api.setSettings(
                    setSettingsRequest {
                        sourceId = en
                        values =
                            Struct
                                .newBuilder()
                                .putFields("show_adult", Value.newBuilder().setBoolValue(true).build())
                                .putFields("quality", Value.newBuilder().setStringValue("low").build())
                                .putFields(
                                    "langs",
                                    Value.newBuilder().setListValue(ListValue.newBuilder().addValues(Value.newBuilder().setStringValue("fr"))).build(),
                                ).build()
                    },
                )
            }.values.fieldsMap
        assertEquals(true, updated.getValue("show_adult").boolValue)
        assertEquals("low", updated.getValue("quality").stringValue)
        assertEquals(listOf("fr"), updated.getValue("langs").listValue.valuesList.map { it.stringValue })

        // The source sees the stored value.
        runBlocking { api.search(searchRequest { sourceId = en; query = "x" }) }
        assertEquals("1", site.requestsTo("/api/search").last().url.queryParameter("pref_adult"))

        fun set(
            key: String,
            value: Value,
        ) = runBlocking { api.setSettings(setSettingsRequest { sourceId = en; values = Struct.newBuilder().putFields(key, value).build() }) }
        assertEquals(Status.Code.INVALID_ARGUMENT, assertFailsWith<StatusException> { set("quality", Value.newBuilder().setStringValue("ultra").build()) }.status.code)
        assertEquals(Status.Code.INVALID_ARGUMENT, assertFailsWith<StatusException> { set("nope", Value.newBuilder().setBoolValue(true).build()) }.status.code)
        val vetoed = assertFailsWith<StatusException> { set("rejects_bad", Value.newBuilder().setStringValue("bad").build()) }
        assertTrue("rejected" in vetoed.status.description!!)
    }

    @Test
    fun `upstream failures map to error reasons`() {
        val missing = assertFailsWith<StatusException> { runBlocking { api.getSeries(getSeriesRequest { sourceId = en; key = "missing" }) } }
        assertEquals(Status.Code.NOT_FOUND, missing.status.code)
        assertEquals("NOT_FOUND", reason(missing))

        site.override = { request -> if (request.url.encodedPath == "/api/popular") MockResponse.Builder().code(503).build() else null }
        try {
            val down = assertFailsWith<StatusException> { runBlocking { api.popular(popularRequest { sourceId = en }) } }
            assertEquals(Status.Code.UNAVAILABLE, down.status.code)
            assertEquals("SOURCE_DOWN", reason(down))

            val http = service.get("/v1/sources/$en/popular")
            assertEquals(503, http.statusCode())
            assertTrue(http.headers().firstValue("content-type").get().startsWith("application/problem+json"))
            assertTrue("\"reason\":\"SOURCE_DOWN\"" in String(http.body()))
        } finally {
            site.override = { null }
        }

        val unknown = assertFailsWith<StatusException> { runBlocking { api.popular(popularRequest { sourceId = "42" }) } }
        assertEquals(Status.Code.NOT_FOUND, unknown.status.code)
    }

    private fun reason(e: StatusException): String? =
        StatusProto
            .fromThrowable(e)
            ?.detailsList
            ?.firstOrNull { it.`is`(ErrorInfo::class.java) }
            ?.unpack(ErrorInfo::class.java)
            ?.reason
}
