// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource

import io.grpc.protobuf.services.ProtoReflectionServiceV1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import panelarr.mihonsource.api.ClientsApi
import panelarr.mihonsource.api.ExtensionsApi
import panelarr.mihonsource.api.IntegrationsApi
import panelarr.mihonsource.api.SourcePluginApi
import panelarr.mihonsource.api.StoresApi
import panelarr.mihonsource.auth.AdminToken
import panelarr.mihonsource.auth.AuthInterceptor
import panelarr.mihonsource.auth.Authenticator
import panelarr.mihonsource.auth.ClientRegistry
import panelarr.mihonsource.auth.Scopes
import panelarr.mihonsource.compat.AndroidHost
import panelarr.mihonsource.config.AppConfig
import panelarr.mihonsource.extension.ExtensionInstaller
import panelarr.mihonsource.extension.ExtensionManager
import panelarr.mihonsource.http.ApiServer
import panelarr.mihonsource.http.HttpAuth
import panelarr.mihonsource.http.ImageService
import panelarr.mihonsource.http.SpaFiles
import panelarr.mihonsource.http.UploadService
import panelarr.mihonsource.mihon.MihonHost
import panelarr.mihonsource.mihon.MihonInstantiator
import panelarr.mihonsource.mihon.MihonSources
import panelarr.mihonsource.net.Downloader
import panelarr.mihonsource.network.FileIntegrationsStore
import panelarr.mihonsource.network.ProfileResolver
import panelarr.mihonsource.network.PushedProfiles
import panelarr.mihonsource.network.SolverClient
import panelarr.mihonsource.network.SourceNetworks
import panelarr.mihonsource.secret.SecretBox
import panelarr.mihonsource.store.StoreManager
import panelarr.mihonsource.store.StoreReader
import java.nio.file.Files

/** Name the service reports about itself. */
const val SERVICE_NAME = "mihon-source"

private val log = LoggerFactory.getLogger("panelarr.mihonsource")

/** Everything the service is made of, wired together. Tests start it on a random port. */
class Application(
    val config: AppConfig,
) {
    private val dataDir = config.dataDir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val adminToken: AdminToken
    val clients: ClientRegistry
    val extensions: ExtensionManager
    val networks: SourceNetworks
    private val server: ApiServer

    init {
        Files.createDirectories(dataDir)
        adminToken = AdminToken.load(dataDir.resolve("auth"), config.adminToken)
        clients = ClientRegistry(dataDir.resolve("auth"))
        val authenticator = Authenticator(adminToken, clients)

        val secrets = SecretBox.load(dataDir.resolve("secret.key"), config.secretKey)
        val integrations = FileIntegrationsStore(dataDir.resolve("config"))
        val resolver = ProfileResolver(integrations, secrets)
        networks = SourceNetworks(resolver, dataDir.resolve("cookies"), SolverClient(Downloader.defaultClient()))
        integrations.addListener(networks::configurationChanged)
        MihonHost.install(dataDir, networks)

        extensions = ExtensionManager(dataDir.resolve("extensions"), MihonInstantiator())
        val installer =
            ExtensionInstaller(extensions, Downloader(), config.maxExtensionBytes) { sourceIds ->
                sourceIds.forEach { id ->
                    AndroidHost.deleteSourcePreferences(id)
                    networks.deleteCookies(id)
                    integrations.assign(id, null)
                    integrations.setPushed(id, null)
                }
            }
        runBlocking { extensions.loadAll() }
        val stores = StoreManager(dataDir.resolve("stores"), StoreReader(Downloader()), extensions, installer)

        val sources = MihonSources(extensions)
        server =
            ApiServer(
                host = config.host,
                port = config.port,
                services =
                    listOf(
                        SourcePluginApi(sources, PushedProfiles(integrations, secrets)) { extensions.list().count { !it.loaded } },
                        ExtensionsApi(extensions, installer),
                        StoresApi(stores),
                        ClientsApi(clients),
                        IntegrationsApi(integrations, resolver, secrets) { extensions.sources().map { it.id } },
                        // Lets tools such as grpcurl discover the API. Needs the admin scope.
                        ProtoReflectionServiceV1.newInstance(),
                    ),
                interceptors = listOf(AuthInterceptor(authenticator)),
                routes =
                    mapOf(
                        "/v1/sources/{sourceId}/image" to HttpAuth(ImageService(sources, scope), authenticator, Scopes.SOURCES_READ),
                        "/v1/admin/extensions/upload" to HttpAuth(UploadService(installer, scope), authenticator, Scopes.ADMIN),
                    ),
                // Base64 in JSON makes uploads a third larger.
                maxRequestLength = config.maxExtensionBytes * 4 / 3 + REQUEST_OVERHEAD,
                spa = SpaFiles.find(config.adminDir),
            )
    }

    /** Starts serving and returns the bound port. */
    fun start(): Int = server.start()

    fun stop() {
        server.stop().join()
        extensions.close()
        scope.cancel()
    }

    private companion object {
        const val REQUEST_OVERHEAD = 1024L * 1024
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
