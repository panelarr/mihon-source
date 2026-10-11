// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.http

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.grpc.StatusException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import panelarr.mihonsource.mihon.MihonSources

/**
 * `GET /v1/sources/{sourceId}/image?ref=...`: streams an image (a Page.ref or an image URL)
 * through the source's own client, the HTTP counterpart of the FetchImage RPC.
 */
class ImageService(
    private val sources: MihonSources,
    private val scope: CoroutineScope,
) : HttpService {
    override fun serve(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): HttpResponse {
        val sourceId = ctx.pathParam("sourceId").orEmpty()
        val ref = ctx.queryParam("ref")
        if (ref.isNullOrEmpty()) return HttpAuth.error(HttpStatus.BAD_REQUEST, "the 'ref' query parameter is required")
        val result =
            scope.future(Dispatchers.IO) {
                try {
                    stream(sources.openImage(sources.get(sourceId), ref))
                } catch (e: StatusException) {
                    Problems.fromStatus(e)
                } catch (e: IllegalArgumentException) {
                    HttpAuth.error(HttpStatus.BAD_REQUEST, e.message ?: "invalid request")
                }
            }
        return HttpResponse.of(result)
    }

    /** Streams [response]'s body to the client, then closes it. */
    private fun stream(response: okhttp3.Response): HttpResponse {
        val body = response.body
        val headers =
            ResponseHeaders
                .builder(HttpStatus.OK)
                .contentType(body.contentType()?.toString()?.let(MediaType::parse) ?: MediaType.OCTET_STREAM)
                .set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=3600")
                .apply { if (body.contentLength() >= 0) contentLength(body.contentLength()) }
                .build()
        val writer = HttpResponse.streaming()
        writer.write(headers)
        scope.launch(Dispatchers.IO) {
            response.use {
                try {
                    body.byteStream().use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (!writer.tryWrite(HttpData.copyOf(buffer, 0, read))) break
                            writer.whenConsumed().join()
                        }
                    }
                    writer.close()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    writer.close(e)
                }
            }
        }
        return writer
    }

    private companion object {
        const val CHUNK_SIZE = 64 * 1024
    }
}
