// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.http

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.file.FileService
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.logging.AccessLogWriter
import io.grpc.BindableService
import io.grpc.ServerInterceptor
import panelarr.mihonsource.BuildInfo
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture

/**
 * The network edge. All RPC stack specifics stay in this package: services are plain
 * gRPC services and auth is a gRPC interceptor, so the transport could be swapped
 * without touching them.
 *
 * Serves every service over gRPC (also gRPC-Web) and over HTTP/JSON transcoded from
 * the `google.api.http` annotations, plus the OpenAPI document and Swagger UI.
 */
class ApiServer(
    private val host: String,
    private val port: Int,
    private val services: List<BindableService>,
    private val interceptors: List<ServerInterceptor>,
) {
    private var server: Server? = null

    fun start(): Int {
        val grpc =
            GrpcService
                .builder()
                .addServices(services)
                .intercept(interceptors)
                .enableHttpJsonTranscoding(true)
                .build()

        val built =
            Server
                .builder()
                .http(InetSocketAddress(host, port))
                .service(grpc)
                .service("/openapi.yaml", openApiDocument())
                .service("/docs") { _, _ -> HttpResponse.ofRedirect(HttpStatus.MOVED_PERMANENTLY, "/docs/") }
                .service("/docs/swagger-initializer.js", swaggerInitializer())
                .serviceUnder("/docs/", swaggerUi())
                .decorator(SecurityHeaders::decorate)
                .decorator(JsonByDefault::decorate)
                .accessLogWriter(AccessLogWriter.common(), true)
                .build()
        built.start().join()
        server = built
        return built.activeLocalPort()
    }

    fun stop(): CompletableFuture<Void> = server?.stop() ?: CompletableFuture.completedFuture(null)

    private fun openApiDocument(): HttpService {
        val bytes =
            requireNotNull(javaClass.getResourceAsStream("/openapi/openapi.yaml")) { "openapi.yaml missing from the build" }
                .use { it.readBytes() }
        return HttpService { _, _ -> HttpResponse.of(HttpStatus.OK, MediaType.parse("application/yaml"), bytes) }
    }

    private fun swaggerUi(): HttpService =
        FileService
            .builder(javaClass.classLoader, "/META-INF/resources/webjars/swagger-ui/${BuildInfo.swaggerUiVersion}")
            .build()

    private fun swaggerInitializer(): HttpService {
        val script =
            """
            window.onload = function () {
              window.ui = SwaggerUIBundle({
                url: "/openapi.yaml",
                dom_id: "#swagger-ui",
                presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
                layout: "StandaloneLayout",
              });
            };
            """.trimIndent()
        return HttpService { _, _ ->
            HttpResponse.of(HttpStatus.OK, MediaType.JAVASCRIPT_UTF_8, script)
        }
    }

    /**
     * Treats API request bodies without a JSON content type as JSON, so a plain
     * `curl -d '{...}'` (which sends a form content type) works.
     */
    private object JsonByDefault {
        private val writeMethods = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)

        fun decorate(
            delegate: HttpService,
            ctx: ServiceRequestContext,
            req: HttpRequest,
        ): HttpResponse {
            val contentType = req.contentType()
            val needsJson =
                req.method() in writeMethods &&
                    req.path().startsWith("/v1/") &&
                    (contentType == null || contentType.`is`(MediaType.FORM_DATA))
            if (!needsJson) return delegate.serve(ctx, req)
            val json =
                req.withHeaders(
                    req
                        .headers()
                        .toBuilder()
                        .contentType(MediaType.JSON_UTF_8)
                        .build(),
                )
            ctx.updateRequest(json)
            return delegate.serve(ctx, json)
        }
    }

    private object SecurityHeaders {
        fun decorate(
            delegate: HttpService,
            ctx: ServiceRequestContext,
            req: HttpRequest,
        ): HttpResponse {
            ctx.addAdditionalResponseHeader(HttpHeaderNames.X_CONTENT_TYPE_OPTIONS, "nosniff")
            ctx.addAdditionalResponseHeader(HttpHeaderNames.REFERRER_POLICY, "no-referrer")
            return delegate.serve(ctx, req)
        }
    }
}
