// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import com.google.protobuf.ByteString
import com.linecorp.armeria.client.grpc.GrpcClients
import kotlinx.coroutines.runBlocking
import panelarr.mihon.v1.Extension
import panelarr.mihon.v1.MihonAdminServiceGrpcKt.MihonAdminServiceCoroutineStub
import panelarr.mihon.v1.MihonIntegrationsServiceGrpcKt.MihonIntegrationsServiceCoroutineStub
import panelarr.mihon.v1.installExtensionRequest
import panelarr.mihonsource.Application
import panelarr.mihonsource.config.AppConfig
import panelarr.source.v1.SourcePluginServiceGrpcKt.SourcePluginServiceCoroutineStub
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/** A running service on a random port with an admin token, plus typed gRPC stubs. */
class TestService(
    val dataDir: Path,
    metricsPort: Int? = null,
    healthCheckInterval: java.time.Duration? = null,
) : AutoCloseable {
    val app =
        Application(
            AppConfig(
                dataDir = dataDir,
                host = "127.0.0.1",
                port = 0,
                adminToken = ADMIN,
                metricsPort = metricsPort,
                healthCheckInterval = healthCheckInterval,
            ),
        )
    val base = "http://127.0.0.1:${app.start()}"
    private val http = HttpClient.newHttpClient()

    inline fun <reified T : Any> stub(token: String? = ADMIN): T =
        GrpcClients
            .builder(base)
            .apply { if (token != null) addHeader("authorization", "Bearer $token") }
            .build(T::class.java)

    val sources: SourcePluginServiceCoroutineStub get() = stub()
    val admin: MihonAdminServiceCoroutineStub get() = stub()
    val integrations: MihonIntegrationsServiceCoroutineStub get() = stub()

    fun install(file: Path): Extension =
        runBlocking {
            admin
                .installExtension(
                    installExtensionRequest {
                        data = ByteString.copyFrom(Files.readAllBytes(file))
                        fileName = file.fileName.toString()
                    },
                ).extension
        }

    fun get(
        path: String,
        token: String? = ADMIN,
    ): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(base + path)).GET()
        if (token != null) request.header("Authorization", "Bearer $token")
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    fun send(
        method: String,
        path: String,
        body: String,
        token: String? = ADMIN,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(base + path)).method(method, HttpRequest.BodyPublishers.ofString(body))
        if (token != null) request.header("Authorization", "Bearer $token")
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    override fun close() = app.stop()

    companion object {
        const val ADMIN = "it-admin-token"
    }
}
