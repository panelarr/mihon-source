// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.http

import com.google.rpc.ErrorInfo
import com.google.rpc.RetryInfo
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.protobuf.StatusProto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Errors over HTTP/JSON as RFC 9457 problem details (`application/problem+json`):
 * `type`, `title`, `status`, `detail`, plus `code` (the gRPC code name), `reason` (from
 * google.rpc.ErrorInfo) and `retryAfter` (seconds, from google.rpc.RetryInfo). `message`
 * repeats `detail` for clients that read gRPC-style errors.
 */
object Problems {
    val PROBLEM_JSON: MediaType = MediaType.parse("application/problem+json; charset=utf-8")

    fun fromStatus(e: StatusException): HttpResponse = fromStatus(e.status, e.trailers)

    fun fromStatus(
        status: Status,
        trailers: Metadata?,
    ): HttpResponse {
        val details = StatusProto.fromStatusAndTrailers(status, trailers).detailsList
        val reason = details.firstOrNull { it.`is`(ErrorInfo::class.java) }?.unpack(ErrorInfo::class.java)?.reason
        val retry =
            details
                .firstOrNull { it.`is`(RetryInfo::class.java) }
                ?.unpack(RetryInfo::class.java)
                ?.retryDelay
                ?.seconds
        return response(status, reason, retry)
    }

    fun response(
        status: Status,
        reason: String?,
        retryAfterSeconds: Long?,
        http: HttpStatus = httpStatus(status.code),
    ): HttpResponse {
        val body =
            buildJsonObject {
                put("type", JsonPrimitive("about:blank"))
                put("title", JsonPrimitive(http.reasonPhrase()))
                put("status", JsonPrimitive(http.code()))
                put("detail", JsonPrimitive(status.description ?: http.reasonPhrase()))
                put("code", JsonPrimitive(status.code.name))
                put("message", JsonPrimitive(status.description ?: http.reasonPhrase()))
                reason?.let { put("reason", JsonPrimitive(it)) }
                retryAfterSeconds?.let { put("retryAfter", JsonPrimitive(it)) }
            }
        val headers =
            ResponseHeaders
                .builder(http)
                .contentType(PROBLEM_JSON)
                .apply { retryAfterSeconds?.let { set(HttpHeaderNames.RETRY_AFTER, it.toString()) } }
                .build()
        return HttpResponse.of(
            headers,
            com.linecorp.armeria.common.HttpData
                .ofUtf8(JsonObject(body).toString()),
        )
    }

    fun httpStatus(code: Status.Code): HttpStatus =
        when (code) {
            Status.Code.OK -> HttpStatus.OK
            Status.Code.INVALID_ARGUMENT, Status.Code.OUT_OF_RANGE -> HttpStatus.BAD_REQUEST
            Status.Code.FAILED_PRECONDITION -> HttpStatus.BAD_REQUEST
            Status.Code.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
            Status.Code.PERMISSION_DENIED -> HttpStatus.FORBIDDEN
            Status.Code.NOT_FOUND -> HttpStatus.NOT_FOUND
            Status.Code.ALREADY_EXISTS, Status.Code.ABORTED -> HttpStatus.CONFLICT
            Status.Code.RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS
            Status.Code.UNIMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED
            Status.Code.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
            Status.Code.DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT
            Status.Code.CANCELLED -> HttpStatus.valueOf(CLIENT_CLOSED)
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }

    private const val CLIENT_CLOSED = 499
}
