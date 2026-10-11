// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat.check

import com.linecorp.armeria.client.grpc.GrpcClients
import io.grpc.StatusException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import panelarr.mihon.v1.AvailableExtension
import panelarr.mihon.v1.CheckStep
import panelarr.mihon.v1.ExtensionStatus
import panelarr.mihon.v1.MihonAdminServiceGrpcKt.MihonAdminServiceCoroutineStub
import panelarr.mihon.v1.MihonStoresServiceGrpcKt.MihonStoresServiceCoroutineStub
import panelarr.mihon.v1.SourceCheck
import panelarr.mihon.v1.addStoreRequest
import panelarr.mihon.v1.checkSourcesRequest
import panelarr.mihon.v1.installFromStoreRequest
import panelarr.mihon.v1.listAvailableRequest
import panelarr.mihon.v1.listStoresRequest
import panelarr.mihon.v1.uninstallExtensionRequest
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** Runs a compatibility check against a running mihon-source, through its admin API. */
class CompatCheck(
    private val options: Options,
    private val progress: (String) -> Unit = {},
) {
    private val admin: MihonAdminServiceCoroutineStub = stub()
    private val stores: MihonStoresServiceCoroutineStub = stub()

    private inline fun <reified T : Any> stub(): T =
        GrpcClients
            .builder(options.url)
            .addHeader("authorization", "Bearer ${options.token}")
            .responseTimeout(Duration.ofMinutes(RESPONSE_TIMEOUT_MINUTES))
            .maxResponseLength(0)
            .build(T::class.java)

    suspend fun run(): Report {
        val started = Instant.now()
        options.store?.let { addStore(it) }
        val candidates =
            select(
                stores
                    .listAvailable(
                        listAvailableRequest {
                            options.lang?.let { lang = it }
                            options.query?.let { query = it }
                        },
                    ).extensionsList,
            )
        val what = if (options.checks.isEmpty()) "load only" else "checks: ${options.checks.joinToString()}"
        progress("Checking ${candidates.size} extension(s) ($what)")
        val limit = Semaphore(options.concurrency)
        val reports =
            coroutineScope {
                candidates.map { offer -> async { limit.withPermit { check(offer) } } }.awaitAll()
            }
        return Report(started.toString(), Instant.now().toString(), options.url, options.checks, reports.sortedBy { it.name.lowercase() })
    }

    private suspend fun addStore(url: String) {
        val known = stores.listStores(listStoresRequest {}).storesList.any { it.url.trimEnd('/') == url.trimEnd('/') }
        if (!known) {
            val store = stores.addStore(addStoreRequest { this.url = url }).store
            progress("Added store ${store.name} (${store.extensionCount} extensions)")
        }
    }

    private fun select(offers: List<AvailableExtension>): List<AvailableExtension> =
        when (val selection = options.selection) {
            Options.Selection.All -> {
                offers
            }

            is Options.Selection.Packages -> {
                offers.filter { it.pkgName in selection.names }
            }

            is Options.Selection.Random -> {
                val random = selection.seed?.let(::Random) ?: Random.Default
                offers.shuffled(random).take(selection.count)
            }
        }.distinctBy { it.pkgName }

    private suspend fun check(offer: AvailableExtension): ExtensionReport {
        val base =
            ExtensionReport(
                pkgName = offer.pkgName,
                name = offer.name,
                version = offer.versionName,
                libVersion = offer.libVersion,
                storeId = offer.storeId,
                status = Status.UNSUPPORTED,
            )
        if (!offer.supported) return base.copy(problem = "unsupported lib", detail = "extensions-lib ${offer.libVersion}")
        val alreadyInstalled = offer.installed && !offer.updateAvailable
        val extension =
            try {
                stores
                    .installFromStore(
                        installFromStoreRequest {
                            storeId = offer.storeId
                            pkgName = offer.pkgName
                        },
                    ).extension
            } catch (e: StatusException) {
                progress("✗ ${offer.name}: install failed: ${e.status.description}")
                return base.copy(status = Status.INSTALL_ERROR, problem = "install", detail = e.status.description)
            }
        try {
            if (extension.status != ExtensionStatus.EXTENSION_STATUS_LOADED) {
                progress("✗ ${offer.name}: ${Problems.classify(extension.loadError)}")
                return base.copy(status = Status.LOAD_ERROR, problem = Problems.classify(extension.loadError), detail = extension.loadError)
            }
            val sources = if (options.checks.isEmpty()) emptyList() else checkSources(extension.sourcesList.map { it.id })
            val report =
                base.copy(
                    status = Status.LOADED,
                    sources =
                        if (sources.isEmpty()) {
                            extension.sourcesList.map { SourceReport(it.id, it.name, it.lang, ok = true, steps = emptyList()) }
                        } else {
                            sources.map(::sourceReport)
                        },
                )
            val failing = report.sources.count { !it.ok }
            progress("${if (failing == 0) "✓" else "~"} ${offer.name}: loaded${if (failing > 0) ", $failing source(s) failing" else ""}")
            return report
        } finally {
            if (!options.keep && !alreadyInstalled) {
                runCatching {
                    admin.uninstallExtension(
                        uninstallExtensionRequest {
                            pkgName = extension.pkgName
                            deleteData = true
                        },
                    )
                }
            }
        }
    }

    private suspend fun checkSources(ids: List<String>): List<SourceCheck> =
        admin
            .checkSources(
                checkSourcesRequest {
                    sourceIds += ids
                    steps += options.checks.map { CheckStep.valueOf("CHECK_STEP_${it.uppercase()}") }
                    options.searchQuery?.let { searchQuery = it }
                    concurrency = 1
                },
            ).resultsList

    private fun sourceReport(check: SourceCheck) =
        SourceReport(
            id = check.sourceId,
            name = check.sourceName,
            lang = check.lang,
            ok = check.ok,
            steps =
                check.stepsList.map { step ->
                    StepReport(
                        step =
                            step.step.name
                                .removePrefix("CHECK_STEP_")
                                .lowercase(),
                        ok = step.ok,
                        skipped = step.skipped,
                        reason = step.reason.ifEmpty { null },
                        message = step.message.ifEmpty { null },
                        detail = step.detail.ifEmpty { null },
                        millis = step.duration.seconds * MILLIS + step.duration.nanos / NANOS_PER_MILLI,
                    )
                },
        )

    private companion object {
        const val RESPONSE_TIMEOUT_MINUTES = 10L
        const val MILLIS = 1000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
