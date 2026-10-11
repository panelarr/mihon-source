// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.http

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.SimpleDecoratingHttpService
import io.netty.util.AttributeKey
import panelarr.mihonsource.auth.Authenticator
import panelarr.mihonsource.auth.Principal
import panelarr.mihonsource.auth.Scopes

/**
 * Token and scope check for the plain HTTP routes that aren't gRPC methods (image
 * streaming, uploads). gRPC methods are checked by the auth interceptor instead.
 */
class HttpAuth(
    delegate: HttpService,
    private val authenticator: Authenticator,
    private val scope: String,
) : SimpleDecoratingHttpService(delegate) {
    override fun serve(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): HttpResponse {
        val token = Authenticator.bearerToken(req.headers().get(HttpHeaderNames.AUTHORIZATION))
        val principal = token?.let(authenticator::authenticate) ?: return error(HttpStatus.UNAUTHORIZED, "a valid bearer token is required")
        if (!Scopes.allows(principal.scopes, scope)) return error(HttpStatus.FORBIDDEN, "this call needs the '$scope' scope")
        ctx.setAttr(PRINCIPAL, principal)
        return unwrap().serve(ctx, req)
    }

    companion object {
        val PRINCIPAL: AttributeKey<Principal> = AttributeKey.valueOf(HttpAuth::class.java, "principal")

        fun decorator(
            authenticator: Authenticator,
            scope: String = Scopes.ADMIN,
        ): (HttpService) -> HttpService = { HttpAuth(it, authenticator, scope) }

        /** Error body in the same shape as transcoded gRPC errors. */
        fun error(
            status: HttpStatus,
            message: String,
        ): HttpResponse {
            val grpc =
                when (status) {
                    HttpStatus.UNAUTHORIZED -> "UNAUTHENTICATED" to 16
                    HttpStatus.FORBIDDEN -> "PERMISSION_DENIED" to 7
                    HttpStatus.NOT_FOUND -> "NOT_FOUND" to 5
                    HttpStatus.BAD_REQUEST -> "INVALID_ARGUMENT" to 3
                    HttpStatus.REQUEST_ENTITY_TOO_LARGE -> "RESOURCE_EXHAUSTED" to 8
                    else -> "INTERNAL" to 13
                }
            val body = """{"code":${grpc.second},"grpc-code":"${grpc.first}","message":${jsonString(message)}}"""
            return HttpResponse.of(status, MediaType.JSON_UTF_8, body)
        }

        private fun jsonString(value: String): String =
            kotlinx.serialization.json.JsonPrimitive(value).toString()
    }
}
