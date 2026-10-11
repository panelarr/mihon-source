// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.http

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import panelarr.mihonsource.mihon.ResultCache

/**
 * `Cache-Control: no-cache` (an HTTP header, or gRPC metadata) makes a call bypass the
 * result cache and fetch fresh data.
 */
class CacheControlInterceptor : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val value = headers.get(CACHE_CONTROL)?.lowercase() ?: return next.startCall(call, headers)
        val bypass = "no-cache" in value || "no-store" in value || "max-age=0" in value
        if (!bypass) return next.startCall(call, headers)
        return Contexts.interceptCall(Context.current().withValue(ResultCache.BYPASS, true), call, headers, next)
    }

    private companion object {
        val CACHE_CONTROL: Metadata.Key<String> = Metadata.Key.of("cache-control", Metadata.ASCII_STRING_MARSHALLER)
    }
}
