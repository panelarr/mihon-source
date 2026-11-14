// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.fixtures

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The website the test extensions read: a JSON API for the lib 1.6 extension and HTML
 * pages for the lib 1.4 one, plus images. Records every request.
 */
class FakeSite : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Overrides for specific paths: return a response to short-circuit the normal one. */
    @Volatile
    var override: (RecordedRequest) -> MockResponse? = { null }

    val baseUrl: String get() = server.url("/").toString().removeSuffix("/")

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return override(request) ?: respond(request)
                }
            }
        server.start()
    }

    fun requestsTo(path: String): List<RecordedRequest> = requests.filter { it.url.encodedPath == path }

    private fun respond(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        return when {
            path == "/api/popular" || path == "/api/latest" || path == "/api/search" -> {
                val page = url.queryParameter("page")?.toInt() ?: 1
                val lang = url.queryParameter("lang") ?: "en"
                val prefix = path.removePrefix("/api/")
                json(
                    """{"items":[{"id":"$prefix-$lang-$page-1","title":"${prefix.replaceFirstChar {
                        it.uppercase()
                    }} $page A","cover":"/covers/a.png"},""" +
                        """{"id":"$prefix-$lang-$page-2","title":"${prefix.replaceFirstChar {
                            it.uppercase()
                        }} $page B"}],"hasNext":${page < 2}}""",
                )
            }

            path.matches(Regex("/api/series/[^/]+")) -> {
                val id = path.substringAfterLast('/')
                if (id == "missing") {
                    MockResponse
                        .Builder()
                        .code(404)
                        .body("gone")
                        .build()
                } else {
                    json(
                        """{"id":"$id","title":"Series $id","author":"Author One, Author Two","artist":"Artist",""" +
                            """"description":"About $id","genres":["Action","Drama"],"status":"ongoing",""" +
                            """"cover":"/covers/$id.png"}""",
                    )
                }
            }

            path.matches(Regex("/api/series/[^/]+/chapters")) -> {
                json(
                    """[{"id":"c2","name":"Chapter 2","number":2,"uploaded":1700000100000,"scanlator":"Group"},""" +
                        """{"id":"c1","name":"Chapter 1","number":1,"date":"2023-11-15T00:13:20+02:00"}]""",
                )
            }

            path.matches(Regex("/api/chapters/[^/]+/pages")) -> {
                json("""[{"index":0,"image":"/images/0.png"},{"index":1,"page":"/api/page/1"}]""")
            }

            path.matches(Regex("/api/page/\\d+")) -> {
                json("""{"image":"/images/${path.substringAfterLast('/')}.png"}""")
            }

            path.startsWith("/images/") || path.startsWith("/covers/") -> {
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "image/png")
                    .body(Buffer().write(IMAGE_BYTES + path.toByteArray()))
                    .build()
            }

            path == "/html/popular" || path == "/html/search" -> {
                html(
                    """<ul><li class="series"><a href="/html/series/one" data-cover="/covers/one.png">Html One</a></li>""" +
                        """<li class="series"><a href="/html/series/two">Html Two</a></li></ul><a class="next" href="#">next</a>""",
                )
            }

            path.startsWith("/html/series/") -> {
                html(
                    """<h1>Html ${path.substringAfterLast(
                        '/',
                    )}</h1><span class="author">Writer</span><p class="description">Old style</p>""" +
                        """<span class="genre">Comedy</span><span class="completed"></span>""" +
                        """<ul><li class="chapter"><a href="/html/read/1" data-number="1">Ch 1</a></li></ul>""",
                )
            }

            path.startsWith("/html/read/") -> {
                html("""<div class="page" data-page="/html/page/0"></div><div class="page" data-page="/html/page/1"></div>""")
            }

            path.startsWith("/web/") -> {
                WebPages.respond(request)
            }

            path.startsWith("/html/page/") -> {
                html("""<img src="/images/html-${path.substringAfterLast('/')}.png">""")
            }

            else -> {
                MockResponse.Builder().code(404).build()
            }
        }
    }

    private fun json(body: String) =
        MockResponse
            .Builder()
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun html(body: String) =
        MockResponse
            .Builder()
            .addHeader("Content-Type", "text/html")
            .body("<html><body>$body</body></html>")
            .build()

    override fun close() {
        server.close()
    }

    companion object {
        /** A PNG signature, enough for tests that only check bytes pass through. */
        val IMAGE_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
