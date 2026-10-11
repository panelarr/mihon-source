// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.network

/** An HTTP response with a non-2xx status. */
class HttpException(
    val code: Int,
) : IllegalStateException("HTTP error $code")
