// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Parses a response body (or [html]) as an HTML document, using the request URL as base. */
fun Response.asJsoup(html: String? = null): Document = Jsoup.parse(html ?: body.string(), request.url.toString())
