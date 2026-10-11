// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.http

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
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

        /** An error response in the same problem+json shape as API errors. */
        fun error(
            status: HttpStatus,
            message: String,
        ): HttpResponse {
            val code =
                when (status) {
                    HttpStatus.UNAUTHORIZED -> io.grpc.Status.UNAUTHENTICATED
                    HttpStatus.FORBIDDEN -> io.grpc.Status.PERMISSION_DENIED
                    HttpStatus.NOT_FOUND -> io.grpc.Status.NOT_FOUND
                    HttpStatus.BAD_REQUEST -> io.grpc.Status.INVALID_ARGUMENT
                    HttpStatus.REQUEST_ENTITY_TOO_LARGE -> io.grpc.Status.RESOURCE_EXHAUSTED
                    else -> io.grpc.Status.INTERNAL
                }
            return Problems.response(code.withDescription(message), null, null, status)
        }
    }
}
