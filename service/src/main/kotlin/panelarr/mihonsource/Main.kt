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
import panelarr.mihonsource.api.JobsApi
import panelarr.mihonsource.api.SourcePluginApi
import panelarr.mihonsource.api.StoresApi
import panelarr.mihonsource.auth.AdminToken
import panelarr.mihonsource.auth.AuthInterceptor
import panelarr.mihonsource.auth.Authenticator
import panelarr.mihonsource.auth.ClientRegistry
import panelarr.mihonsource.auth.Scopes
import panelarr.mihonsource.compat.AndroidHost
import panelarr.mihonsource.config.AppConfig
import panelarr.mihonsource.extension.DateFormatRewriter
import panelarr.mihonsource.extension.ExtensionIcons
import panelarr.mihonsource.extension.ExtensionInstaller
import panelarr.mihonsource.extension.ExtensionListener
import panelarr.mihonsource.extension.ExtensionManager
import panelarr.mihonsource.extension.HostedSource
import panelarr.mihonsource.healthcheck.healthcheck
import panelarr.mihonsource.healthcheck.healthcheckUrl
import panelarr.mihonsource.http.ApiServer
import panelarr.mihonsource.http.CacheControlInterceptor
import panelarr.mihonsource.http.HttpAuth
import panelarr.mihonsource.http.IconService
import panelarr.mihonsource.http.ImageService
import panelarr.mihonsource.http.OpsService
import panelarr.mihonsource.http.SpaFiles
import panelarr.mihonsource.http.UploadService
import panelarr.mihonsource.jobs.JobRunner
import panelarr.mihonsource.metrics.Metrics
import panelarr.mihonsource.mihon.MihonHost
import panelarr.mihonsource.mihon.MihonInstantiator
import panelarr.mihonsource.mihon.MihonSources
import panelarr.mihonsource.mihon.ResultCache
import panelarr.mihonsource.mihon.SourceHealthJob
import panelarr.mihonsource.mihon.SourceHealthService
import panelarr.mihonsource.mihon.SourceProber
import panelarr.mihonsource.net.Downloader
import panelarr.mihonsource.network.FileIntegrationsStore
import panelarr.mihonsource.network.ProfileResolver
import panelarr.mihonsource.network.PushedProfiles
import panelarr.mihonsource.network.SolverClient
import panelarr.mihonsource.network.SourceNetworks
import panelarr.mihonsource.secret.SecretBox
import panelarr.mihonsource.store.StoreIcons
import panelarr.mihonsource.store.StoreManager
import panelarr.mihonsource.store.StoreReader
import panelarr.mihonsource.store.StoreRefreshJob
import java.nio.file.Files
import kotlin.system.exitProcess

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
    val metrics = Metrics()

    /** Identifies this run of the service (Health.instance_id). */
    val instanceId: String =
        java.util.UUID
            .randomUUID()
            .toString()
    val jobs: JobRunner
    private val server: ApiServer

    @Volatile
    private var ready = false

    init {
        Files.createDirectories(dataDir)
        adminToken = AdminToken.load(dataDir.resolve("auth"), config.adminToken)
        clients = ClientRegistry(dataDir.resolve("auth"))
        val authenticator = Authenticator(adminToken, clients)

        val secrets = SecretBox.load(dataDir.resolve("secret.key"), config.secretKey)
        val integrations = FileIntegrationsStore(dataDir.resolve("config"))
        val resolver = ProfileResolver(integrations, secrets)
        networks = SourceNetworks(resolver, dataDir.resolve("cookies"), SolverClient(Downloader.defaultClient(), metrics))
        integrations.addListener(networks::configurationChanged)
        MihonHost.install(dataDir, networks)

        extensions = ExtensionManager(dataDir.resolve("extensions"), MihonInstantiator(), transform = DateFormatRewriter::rewrite)
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
        val downloader = Downloader()
        val iconFetcher: suspend (String) -> ByteArray? = { url ->
            runCatching { downloader.fetch(url, ExtensionIcons.MAX_BYTES.toLong()) }.getOrNull()?.takeIf {
                ExtensionIcons.contentType(it) !=
                    null
            }
        }
        val stores = StoreManager(dataDir.resolve("stores"), StoreReader(downloader), extensions, installer, iconFetcher)
        val storeIcons = StoreIcons(dataDir.resolve("cache/store-icons"), stores, downloader)

        metrics.extensionGauges(
            loaded = { extensions.list().count { it.loaded } },
            failed = { extensions.list().count { !it.loaded } },
            sources = { extensions.sources().size },
        )
        val sources = MihonSources(extensions, metrics)
        val prober = SourceProber(sources)
        val health = SourceHealthService(dataDir.resolve("config/source-health.json"), metrics)
        jobs =
            JobRunner(
                dataDir.resolve("jobs"),
                listOf(
                    StoreRefreshJob(stores, config.storeRefreshInterval),
                    SourceHealthJob(
                        sources,
                        prober,
                        health,
                        config.healthCheckInterval,
                        config.healthCheckLangs,
                        config.healthCheckSample,
                    ),
                ),
                scope,
                keep = config.jobHistory.coerceAtLeast(1),
                metrics = metrics,
            )
        sources.healthOf = health::get
        sources.checksEnabled = health::enabled
        val metricsService = OpsService.metrics(metrics::scrape)
        val cache =
            ResultCache(
                ttls = config.cacheTtls.mapKeys { ResultCache.Kind.valueOf(it.key.uppercase()) },
                maxBytes = config.cacheMaxBytes,
                metrics = metrics,
            )
        // A profile or assignment change can change any source's results.
        integrations.addListener(cache::clear)
        extensions.addListener(
            object : ExtensionListener {
                override fun onSourcesAdded(sources: List<HostedSource>) = sources.forEach { cache.invalidate(it.id) }

                override fun onSourcesRemoved(sources: List<HostedSource>) = sources.forEach { cache.invalidate(it.id) }
            },
        )
        server =
            ApiServer(
                host = config.host,
                port = config.port,
                services =
                    listOf(
                        SourcePluginApi(
                            sources,
                            PushedProfiles(integrations, secrets),
                            cache,
                            failedExtensions = { extensions.list().count { !it.loaded } },
                            instanceId = instanceId,
                            profiles = resolver::resolve,
                        ),
                        ExtensionsApi(extensions, installer, sources, prober, health),
                        StoresApi(stores),
                        JobsApi(jobs),
                        ClientsApi(clients),
                        IntegrationsApi(integrations, resolver, secrets) { extensions.sources().map { it.id } },
                        // Lets tools such as grpcurl discover the API. Needs the admin scope.
                        ProtoReflectionServiceV1.newInstance(),
                    ),
                interceptors = listOf(AuthInterceptor(authenticator), CacheControlInterceptor()),
                routes =
                    mapOf(
                        "/healthz" to OpsService.liveness(),
                        "/readyz" to OpsService.readiness { ready },
                        "/metrics" to HttpAuth(metricsService, authenticator, Scopes.METRICS),
                        "/v1/sources/{sourceId}/image" to HttpAuth(ImageService(sources, scope), authenticator, Scopes.SOURCES_READ),
                        "/v1/admin/extensions/upload" to HttpAuth(UploadService(installer, scope), authenticator, Scopes.ADMIN),
                        "/v1/sources/{sourceId}/icon" to
                            HttpAuth(IconService.forSources(extensions, scope), authenticator, Scopes.SOURCES_READ),
                        "/v1/admin/extensions/{pkgName}/icon" to
                            HttpAuth(IconService.forExtensions(extensions, scope), authenticator, Scopes.ADMIN),
                        "/v1/admin/stores/{storeId}/extensions/{pkgName}/icon" to
                            HttpAuth(IconService.forStores(storeIcons, scope), authenticator, Scopes.ADMIN),
                    ),
                // Base64 in JSON makes uploads a third larger.
                maxRequestLength = config.maxExtensionBytes * 4 / 3 + REQUEST_OVERHEAD,
                spa = SpaFiles.find(config.adminDir),
                metricsPort = config.metricsPort,
                metrics = metricsService,
                meterRegistry = metrics.registry,
            )
    }

    /** Starts serving and returns the bound port. */
    fun start(): Int =
        server.start().also {
            ready = true
            jobs.startSchedules()
        }

    /** The bound separate metrics port, if one was configured. */
    fun metricsPort(): Int? = server.metricsLocalPort()

    fun stop() {
        jobs.stop()
        server.stop().join()
        extensions.close()
        scope.cancel()
    }

    private companion object {
        const val REQUEST_OVERHEAD = 1024L * 1024
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "healthcheck") {
        exitProcess(healthcheck(args.getOrNull(1) ?: healthcheckUrl(System.getenv())))
    }
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
