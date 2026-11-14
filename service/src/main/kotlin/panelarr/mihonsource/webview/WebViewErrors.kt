// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import java.io.IOException

/** Something went wrong talking to the browser service. */
open class WebViewException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The browser service is configured but can't be reached, or the connection dropped. */
class WebViewUnavailableException(
    message: String,
    cause: Throwable? = null,
) : WebViewException(message, cause)

/** A command, or a page load, took longer than allowed. */
class CdpTimeoutException(
    message: String,
) : WebViewException(message)

/** The browser answered a command with an error, or with something unexpected. */
class CdpProtocolException(
    message: String,
    val code: Long? = null,
) : WebViewException(message)

/** A page didn't load: a network error, or a page that doesn't exist. */
class CdpLoadException(
    message: String,
) : WebViewException(message)
