// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.fixtures

import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * The pages the WebView test extension reads, under `/web/` of [FakeSite]. Their data only
 * exists once their scripts ran, so nothing can be read from them without a browser.
 */
object WebPages {
    fun respond(request: RecordedRequest): MockResponse {
        val url = request.url
        return when (url.encodedPath) {
            "/web/popular" -> {
                val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                page(
                    "Popular",
                    """
                    var items = [];
                    for (var i = 1; i <= 2; i++) items.push({ id: 'web-$page-' + i, title: 'Web $page ' + String.fromCharCode(64 + i) });
                    window.pageData = JSON.stringify({ items: items, hasNext: $page < 2 });
                    // The extension's shouldOverrideUrlLoading blocks this; the site never sees /web/trap.
                    window.addEventListener('load', function () { setTimeout(function () { location.href = '/web/trap'; }, 0); });
                    """,
                )
            }

            "/web/search" -> {
                page(
                    "Search",
                    """
                    var q = new URLSearchParams(location.search).get('q');
                    document.addEventListener('DOMContentLoaded', function () {
                      bridge.deliver(JSON.stringify({ items: [{ id: 'web-found', title: 'Found: ' + q }] }));
                    });
                    """,
                )
            }

            "/web/series" -> {
                val id = url.queryParameter("id").orEmpty()
                // Without the extension's header the page has nothing to show.
                if (request.headers["X-Fixture"] != "webview") {
                    html("<p id=\"data\">no header</p>")
                } else {
                    html(
                        """
                        <head><title>Series</title><script src="/web/needs.js"></script></head>
                        <body><p id="data"></p><script>
                        document.getElementById('data').textContent = JSON.stringify({
                          id: ${quote(id)}, title: 'Web series ' + ${quote(id)}, author: 'Web Author',
                          description: 'Rendered by script', secret: window.secret || null });
                        </script></body>
                        """,
                    )
                }
            }

            "/web/api/chapters" -> {
                val id = url.queryParameter("id").orEmpty()
                json("""[{"id":"$id-c2","name":"Web chapter 2","number":2},{"id":"$id-c1","name":"Web chapter 1","number":1}]""")
            }

            "/web/reader" -> {
                val chapter = url.queryParameter("ch").orEmpty()
                page("Reader", "document.cookie = 'reader_token=tok-' + ${quote(chapter)} + '; path=/';")
            }

            "/web/api/pages" -> {
                val chapter = url.queryParameter("ch").orEmpty()
                if (request.headers["X-Reader-Token"] != "tok-$chapter") {
                    MockResponse
                        .Builder()
                        .code(403)
                        .body("no token")
                        .build()
                } else {
                    json("""[{"index":0,"image":"/images/$chapter-0.png"},{"index":1,"image":"/images/$chapter-1.png"}]""")
                }
            }

            else -> {
                // Including /web/needs.js and /web/trap: only the extension can make those exist.
                MockResponse
                    .Builder()
                    .code(404)
                    .body("not here")
                    .build()
            }
        }
    }

    private fun quote(text: String): String =
        kotlinx.serialization.json
            .JsonPrimitive(text)
            .toString()

    private fun page(
        title: String,
        script: String,
    ) = html("<head><title>$title</title></head><body><script>${script.trimIndent()}</script></body>")

    private fun html(body: String) =
        MockResponse
            .Builder()
            .addHeader("Content-Type", "text/html; charset=utf-8")
            .body("<!doctype html><html>${body.trimIndent()}</html>")
            .build()

    private fun json(body: String) =
        MockResponse
            .Builder()
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()
}
