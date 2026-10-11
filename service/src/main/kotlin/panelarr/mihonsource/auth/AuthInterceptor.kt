package panelarr.mihonsource.auth

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

/**
 * Checks the bearer token and scope of every gRPC call, including calls that arrive as
 * HTTP/JSON and are transcoded. The caller is available to services through [current].
 */
class AuthInterceptor(
    private val authenticator: Authenticator,
) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val requirement = MethodScopes.requirement(call.methodDescriptor.fullMethodName)
        if (requirement == MethodScopes.Requirement.None) return next.startCall(call, headers)

        val token = Authenticator.bearerToken(headers.get(AUTHORIZATION))
        val principal = token?.let(authenticator::authenticate)
        val failure =
            when {
                principal == null -> {
                    Status.UNAUTHENTICATED.withDescription("a valid bearer token is required")
                }

                requirement is MethodScopes.Requirement.Scope && !Scopes.allows(principal.scopes, requirement.scope) -> {
                    Status.PERMISSION_DENIED.withDescription("this call needs the '${requirement.scope}' scope")
                }

                else -> {
                    null
                }
            }
        if (failure != null) {
            call.close(failure, Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        val context = Context.current().withValue(PRINCIPAL, principal)
        return Contexts.interceptCall(context, call, headers, next)
    }

    companion object {
        private val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        private val PRINCIPAL: Context.Key<Principal> = Context.key("principal")

        /** The caller of the current call, or null for calls that need no credentials. */
        fun current(): Principal? = PRINCIPAL.get()
    }
}
