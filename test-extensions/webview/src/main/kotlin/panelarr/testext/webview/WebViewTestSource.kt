// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.testext.webview

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.GET
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
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A source that reads its site through a WebView, the way extensions for script-heavy sites
 * do: it builds the WebView on the main looper, waits on its own thread for a callback, and
 * destroys the WebView afterwards. Each call exercises a different part of the WebView:
 * - popular: `onPageFinished` + `evaluateJavascript` (a JSON string, decoded twice), while
 *   `shouldOverrideUrlLoading` blocks the page's own redirect;
 * - search: a JavaScript interface the page calls;
 * - details: extra headers on `loadUrl`, and `shouldInterceptRequest` serving a script;
 * - chapters: `loadDataWithBaseURL`, whose page fetches from the base URL's origin;
 * - pages: `onPageStarted`, and a cookie the page sets, read through `CookieManager` and
 *   sent with a plain OkHttp request.
 */
class WebViewTestSource : HttpSource() {
    override val name = "WebView Test"
    override val lang = "en"
    override val supportsLatest = false

    override val baseUrl: String
        get() = System.getProperty("panelarr.testext.baseUrl", "http://127.0.0.1:1")

    private val json = Json { ignoreUnknownKeys = true }
    private val handler = Handler(Looper.getMainLooper())

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("X-Test-Source", "webview")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val result =
            render("$baseUrl/web/popular?page=$page") { view, done ->
                view.evaluateJavascript("window.pageData") { value -> done(value) }
            }
        // evaluateJavascript answers JSON: the page's JSON string arrives quoted.
        return list(json.decodeFromString<String>(result))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException("no latest")

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val url =
            "$baseUrl/web/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", query)
                .build()
                .toString()
        return list(render(url, viaBridge = true))
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = if (fetchDetails) details(manga) else manga
        val chapterList = if (fetchChapters) chapters(manga) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun details(manga: SManga): SManga {
        val target = if (manga.url == UNREACHABLE) "http://127.0.0.1:1/" else "$baseUrl/web/series?id=${manga.url}"
        val result =
            render(target, headers = mapOf("X-Fixture" to "webview")) { view, done ->
                view.evaluateJavascript("document.getElementById('data').textContent") { value -> done(value) }
            }
        val dto = json.decodeFromString<SeriesDto>(json.decodeFromString<String>(result))
        if (dto.secret != SECRET) throw IOException("the page didn't get the script the extension serves")
        return SManga.create().apply {
            url = dto.id
            title = dto.title
            author = dto.author
            description = dto.description
            status = SManga.ONGOING
            initialized = true
        }
    }

    private fun chapters(manga: SManga): List<SChapter> {
        // Built here, shown as if it came from the site, so its fetch() is same-origin.
        val html =
            """
            <html><head><title>Chapters</title></head><body><script>
            fetch('/web/api/chapters?id=' + encodeURIComponent(${JsonPrimitive(manga.url)}))
              .then(function (r) { return r.text(); })
              .then(function (text) { bridge.deliver(text); });
            </script></body></html>
            """.trimIndent()
        val result = render("$baseUrl/web/", viaBridge = true, data = html)
        return json.decodeFromString<List<ChapterDto>>(result).map { dto ->
            SChapter.create().apply {
                url = dto.id
                name = dto.name
                chapter_number = dto.number
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val reader = "$baseUrl/web/reader?ch=${chapter.url}"
        render(reader, requireStartedAt = reader) { _, done -> done("") }
        val token =
            CookieManager
                .getInstance()
                .getCookie(baseUrl)
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("reader_token=") }
                ?.substringAfter('=')
                ?: throw IOException("the reader page set no token cookie")
        val request = GET("$baseUrl/web/api/pages?ch=${chapter.url}", headers.newBuilder().add("X-Reader-Token", token).build())
        val pages =
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("pages answered HTTP ${response.code}")
                json.decodeFromString<List<PageDto>>(response.body.string())
            }
        return pages.map { Page(it.index, imageUrl = baseUrl + it.image) }
    }

    override suspend fun getImageUrl(page: Page): String = page.imageUrl ?: throw IOException("no image")

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/web/series?id=${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/web/reader?ch=${chapter.url}"

    private fun list(text: String): MangasPage {
        val dto = json.decodeFromString<ListDto>(text)
        return MangasPage(
            dto.items.map { item ->
                SManga.create().apply {
                    url = item.id
                    title = item.title
                }
            },
            dto.hasNext,
        )
    }

    /**
     * Loads [url] in a new WebView on the main looper and waits for a result: from
     * [onFinished] once the page loaded, or from the page itself through the `bridge`
     * interface with [viaBridge]. With [data], that HTML is shown with [url] as its base.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun render(
        url: String,
        headers: Map<String, String> = emptyMap(),
        viaBridge: Boolean = false,
        data: String? = null,
        requireStartedAt: String? = null,
        onFinished: ((WebView, (String) -> Unit) -> Unit)? = null,
    ): String {
        val latch = CountDownLatch(1)
        var result: String? = null
        var failure: String? = null
        var webView: WebView? = null
        val done = { value: String ->
            if (result == null && failure == null) result = value
            latch.countDown()
        }
        val fail = { message: String ->
            if (result == null && failure == null) failure = message
            latch.countDown()
        }
        handler.post {
            try {
                val view = WebView(Injekt.get<Application>())
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.userAgentString = USER_AGENT
                if (viaBridge) view.addJavascriptInterface(Bridge(done), "bridge")
                var started: String? = null
                view.webViewClient =
                    object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView,
                            url: String,
                            favicon: Bitmap?,
                        ) {
                            if (started == null) started = url
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            if (requireStartedAt != null && started != requireStartedAt) {
                                fail("onPageStarted saw $started, not $requireStartedAt")
                                return
                            }
                            onFinished?.invoke(view, done)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = request.url.path == "/web/trap"

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            if (request.url.path != "/web/needs.js") return null
                            return WebResourceResponse("application/javascript", "utf-8", "window.secret = '$SECRET';".byteInputStream())
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) fail("the page failed: ${error.description}")
                        }
                    }
                if (data != null) {
                    view.loadDataWithBaseURL(url, data, "text/html", "utf-8", null)
                } else {
                    view.loadUrl(url, headers)
                }
            } catch (e: RuntimeException) {
                fail(e.message ?: e.javaClass.name)
            }
        }
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw IOException("the WebView timed out")
        } finally {
            handler.post {
                webView?.stopLoading()
                webView?.destroy()
            }
        }
        failure?.let { throw IOException(it) }
        return result ?: throw IOException("the WebView gave no result")
    }

    /** What the page's script calls as `bridge.deliver(...)`. */
    class Bridge(
        private val done: (String) -> Unit,
    ) {
        @JavascriptInterface
        fun deliver(value: String) = done(value)
    }

    @Serializable
    private data class ListDto(
        val items: List<ItemDto>,
        val hasNext: Boolean = false,
    )

    @Serializable
    private data class ItemDto(
        val id: String,
        val title: String,
    )

    @Serializable
    private data class SeriesDto(
        val id: String,
        val title: String,
        val author: String? = null,
        val description: String? = null,
        val secret: String? = null,
    )

    @Serializable
    private data class ChapterDto(
        val id: String,
        val name: String,
        val number: Float,
    )

    @Serializable
    private data class PageDto(
        val index: Int,
        val image: String,
    )

    companion object {
        /** A series whose page can't be reached, to see load errors arrive. */
        const val UNREACHABLE = "unreachable"
        const val SECRET = "from-extension"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Test) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
        private const val TIMEOUT_SECONDS = 30L
    }
}
