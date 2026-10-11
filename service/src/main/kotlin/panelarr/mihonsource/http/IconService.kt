// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.http

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.grpc.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import panelarr.mihonsource.extension.ExtensionIcons
import panelarr.mihonsource.extension.ExtensionManager
import panelarr.mihonsource.mihon.MihonSource
import panelarr.mihonsource.store.StoreIcons

/**
 * Icons, served by the service so clients (and the admin UI under its CSP) never load
 * images from third parties:
 * - `GET /v1/sources/{sourceId}/icon`: the icon of a source's extension (`sources:read`).
 * - `GET /v1/admin/extensions/{pkgName}/icon`: an installed extension's icon (`admin`).
 * - `GET /v1/admin/stores/{storeId}/extensions/{pkgName}/icon`: a store's icon for an
 *   extension it offers, fetched on first use and cached (`admin`).
 */
class IconService private constructor(
    private val lookup: suspend (ServiceRequestContext) -> ExtensionIcons.Icon?,
    private val scope: CoroutineScope,
) : HttpService {
    override fun serve(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): HttpResponse =
        HttpResponse.of(
            scope.future(Dispatchers.IO) {
                val icon =
                    try {
                        lookup(ctx)
                    } catch (e: IllegalArgumentException) {
                        return@future HttpAuth.error(HttpStatus.BAD_REQUEST, e.message ?: "invalid request")
                    }
                if (icon == null) {
                    Problems.response(Status.NOT_FOUND.withDescription("no icon"), null, null)
                } else {
                    val headers =
                        ResponseHeaders
                            .builder(HttpStatus.OK)
                            .contentType(MediaType.parse(icon.contentType))
                            .set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=86400")
                            .build()
                    HttpResponse.of(
                        headers,
                        com.linecorp.armeria.common.HttpData
                            .wrap(icon.bytes),
                    )
                }
            },
        )

    companion object {
        fun forSources(
            extensions: ExtensionManager,
            scope: CoroutineScope,
        ) = IconService({ ctx ->
            val id = ctx.pathParam("sourceId")?.toLongOrNull() ?: throw IllegalArgumentException("invalid source id")
            val source = extensions.sources().firstOrNull { it.id == id } as? MihonSource
            source?.let { extensions.icons.read(it.extension.pkgName) }
        }, scope)

        fun forExtensions(
            extensions: ExtensionManager,
            scope: CoroutineScope,
        ) = IconService({ ctx -> extensions.icons.read(ctx.pathParam("pkgName").orEmpty()) }, scope)

        fun forStores(
            icons: StoreIcons,
            scope: CoroutineScope,
        ) = IconService({ ctx -> icons.get(ctx.pathParam("storeId").orEmpty(), ctx.pathParam("pkgName").orEmpty()) }, scope)
    }
}
