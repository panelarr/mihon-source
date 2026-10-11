// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource

import io.grpc.protobuf.services.ProtoReflectionServiceV1
import org.slf4j.LoggerFactory
import panelarr.mihonsource.api.ClientsApi
import panelarr.mihonsource.api.SourcePluginApi
import panelarr.mihonsource.auth.AdminToken
import panelarr.mihonsource.auth.AuthInterceptor
import panelarr.mihonsource.auth.Authenticator
import panelarr.mihonsource.auth.ClientRegistry
import panelarr.mihonsource.config.AppConfig
import panelarr.mihonsource.http.ApiServer
import java.nio.file.Files

/** Name the service reports about itself. */
const val SERVICE_NAME = "mihon-source"

private val log = LoggerFactory.getLogger("panelarr.mihonsource")

/** Everything the service is made of, wired together. Tests start it on a random port. */
class Application(
    val config: AppConfig,
) {
    private val authDir = config.dataDir.resolve("auth")
    val adminToken: AdminToken
    val clients: ClientRegistry
    private val server: ApiServer

    init {
        Files.createDirectories(config.dataDir)
        adminToken = AdminToken.load(authDir, config.adminToken)
        clients = ClientRegistry(authDir)
        val authenticator = Authenticator(adminToken, clients)
        server =
            ApiServer(
                host = config.host,
                port = config.port,
                services =
                    listOf(
                        SourcePluginApi { emptyList() },
                        ClientsApi(clients),
                        // Lets tools such as grpcurl discover the API. Needs the admin scope.
                        ProtoReflectionServiceV1.newInstance(),
                    ),
                interceptors = listOf(AuthInterceptor(authenticator)),
            )
    }

    /** Starts serving and returns the bound port. */
    fun start(): Int = server.start()

    fun stop() {
        server.stop().join()
    }
}

fun main() {
    val app = Application(AppConfig.fromEnv())
    val port = app.start()
    Runtime.getRuntime().addShutdownHook(Thread { app.stop() })
    log.info("{} {} listening on {}:{} (data in {})", SERVICE_NAME, BuildInfo.version, app.config.host, port, app.config.dataDir)
    app.adminToken.generated?.let { token ->
        // Printed once, to stdout rather than the log, so it isn't kept in log files.
        println(
            """
            |
            |================================================================
            | Generated admin token (shown only now, stored only as a hash):
            |   $token
            | Set MIHON_ADMIN_TOKEN to use your own token instead.
            |================================================================
            |
            """.trimMargin(),
        )
    }
}
