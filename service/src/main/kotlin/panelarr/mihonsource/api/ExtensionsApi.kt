// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import panelarr.mihon.v1.CheckSourcesRequest
import panelarr.mihon.v1.CheckSourcesResponse
import panelarr.mihon.v1.CheckStep
import panelarr.mihon.v1.ContentWarning
import panelarr.mihon.v1.Extension
import panelarr.mihon.v1.ExtensionFormat
import panelarr.mihon.v1.ExtensionStatus
import panelarr.mihon.v1.GetExtensionRequest
import panelarr.mihon.v1.GetExtensionResponse
import panelarr.mihon.v1.InstallExtensionRequest
import panelarr.mihon.v1.InstallExtensionResponse
import panelarr.mihon.v1.ListExtensionsRequest
import panelarr.mihon.v1.ListExtensionsResponse
import panelarr.mihon.v1.MihonAdminServiceGrpcKt
import panelarr.mihon.v1.ReloadExtensionRequest
import panelarr.mihon.v1.ReloadExtensionResponse
import panelarr.mihon.v1.SetExtensionLanguagesRequest
import panelarr.mihon.v1.SetExtensionLanguagesResponse
import panelarr.mihon.v1.SetHealthCheckRequest
import panelarr.mihon.v1.SetHealthCheckResponse
import panelarr.mihon.v1.SourceCheck
import panelarr.mihon.v1.UninstallExtensionRequest
import panelarr.mihon.v1.UninstallExtensionResponse
import panelarr.mihon.v1.checkSourcesResponse
import panelarr.mihon.v1.extension
import panelarr.mihon.v1.extensionOrigin
import panelarr.mihon.v1.extensionSource
import panelarr.mihon.v1.getExtensionResponse
import panelarr.mihon.v1.installExtensionResponse
import panelarr.mihon.v1.listExtensionsResponse
import panelarr.mihon.v1.reloadExtensionResponse
import panelarr.mihon.v1.setExtensionLanguagesResponse
import panelarr.mihon.v1.setHealthCheckResponse
import panelarr.mihon.v1.sourceCheck
import panelarr.mihon.v1.stepResult
import panelarr.mihon.v1.uninstallExtensionResponse
import panelarr.mihonsource.extension.Expectations
import panelarr.mihonsource.extension.ExtensionInstaller
import panelarr.mihonsource.extension.ExtensionManager
import panelarr.mihonsource.extension.ExtensionRecord
import panelarr.mihonsource.extension.InstalledExtension
import panelarr.mihonsource.mihon.CheckStepKind
import panelarr.mihonsource.mihon.MihonSources
import panelarr.mihonsource.mihon.SourceCheckResult
import panelarr.mihonsource.mihon.SourceHealthService
import panelarr.mihonsource.mihon.SourceProber
import java.time.Instant

/** `panelarr.mihon.v1.MihonAdminService`: installed extensions. */
class ExtensionsApi(
    private val manager: ExtensionManager,
    private val installer: ExtensionInstaller,
    private val sources: MihonSources,
    private val prober: SourceProber,
    private val health: SourceHealthService,
) : MihonAdminServiceGrpcKt.MihonAdminServiceCoroutineImplBase() {
    override suspend fun checkSources(request: CheckSourcesRequest): CheckSourcesResponse =
        rpc {
            val selected =
                if (request.sourceIdsCount == 0) sources.all() else request.sourceIdsList.map { sources.get(it) }
            val steps = request.stepsList.mapNotNull(::stepKind).toSet()
            val limit =
                Semaphore(
                    request.concurrency
                        .takeIf { it > 0 }
                        ?.toInt()
                        ?.coerceAtMost(MAX_CONCURRENCY) ?: DEFAULT_CONCURRENCY,
                )
            val results =
                coroutineScope {
                    selected
                        .map { source ->
                            async { limit.withPermit { prober.probe(source, steps, request.searchQuery.ifBlank { null }) } }
                        }.awaitAll()
                }
            results.forEach(health::record)
            checkSourcesResponse { this.results += results.map { it.toProto() } }
        }

    override suspend fun setHealthCheck(request: SetHealthCheckRequest): SetHealthCheckResponse =
        rpc {
            health.setEnabled(sources.get(request.sourceId).id, request.enabled)
            setHealthCheckResponse {}
        }

    override suspend fun listExtensions(request: ListExtensionsRequest): ListExtensionsResponse =
        rpc { listExtensionsResponse { extensions += manager.list().map { it.toProto() } } }

    override suspend fun getExtension(request: GetExtensionRequest): GetExtensionResponse =
        rpc { getExtensionResponse { extension = manager.get(request.pkgName).toProto() } }

    override suspend fun installExtension(request: InstallExtensionRequest): InstallExtensionResponse =
        rpc {
            val expectations =
                Expectations(
                    sha256 = request.expectedSha256.ifBlank { null },
                    signingFingerprint = request.expectedSigningFingerprint.ifBlank { null },
                )
            val installed =
                when (request.fileCase) {
                    InstallExtensionRequest.FileCase.URL -> {
                        installer.installFromUrl(request.url, expectations)
                    }

                    InstallExtensionRequest.FileCase.DATA -> {
                        installer.installFromBytes(request.data.toByteArray(), request.fileName, expectations)
                    }

                    else -> {
                        throw IllegalArgumentException("either url or data is required")
                    }
                }
            installExtensionResponse { extension = installed.toProto() }
        }

    override suspend fun uninstallExtension(request: UninstallExtensionRequest): UninstallExtensionResponse =
        rpc {
            installer.uninstall(request.pkgName, deleteData = request.deleteData)
            uninstallExtensionResponse {}
        }

    override suspend fun setExtensionLanguages(request: SetExtensionLanguagesRequest): SetExtensionLanguagesResponse =
        rpc { setExtensionLanguagesResponse { extension = manager.setLanguages(request.pkgName, request.enabledLangsList).toProto() } }

    override suspend fun reloadExtension(request: ReloadExtensionRequest): ReloadExtensionResponse =
        rpc { reloadExtensionResponse { extension = manager.reload(request.pkgName).toProto() } }
}

private const val DEFAULT_CONCURRENCY = 4
private const val MAX_CONCURRENCY = 32

private fun stepKind(step: CheckStep): CheckStepKind? =
    when (step) {
        CheckStep.CHECK_STEP_POPULAR -> CheckStepKind.POPULAR
        CheckStep.CHECK_STEP_LATEST -> CheckStepKind.LATEST
        CheckStep.CHECK_STEP_SEARCH -> CheckStepKind.SEARCH
        CheckStep.CHECK_STEP_DETAILS -> CheckStepKind.DETAILS
        CheckStep.CHECK_STEP_CHAPTERS -> CheckStepKind.CHAPTERS
        CheckStep.CHECK_STEP_PAGES -> CheckStepKind.PAGES
        CheckStep.CHECK_STEP_IMAGE -> CheckStepKind.IMAGE
        else -> null
    }

private fun SourceCheckResult.toProto(): SourceCheck {
    val result = this
    return sourceCheck {
        sourceId = result.source.id.toString()
        sourceName = result.source.name
        lang = result.source.lang
        pkgName = result.source.extension.pkgName
        ok = result.ok
        checkedAt = result.checkedAt.toTimestamp()
        steps +=
            result.steps.map { outcome ->
                stepResult {
                    step = CheckStep.valueOf("CHECK_STEP_${outcome.step.name}")
                    ok = outcome.ok
                    skipped = outcome.skipped
                    outcome.reason?.let { reason = it }
                    outcome.message?.let { message = it }
                    outcome.detail?.let { detail = it }
                    duration = millis(outcome.duration.toMillis())
                }
            }
    }
}

internal fun InstalledExtension.toProto(): Extension {
    val installed = this
    val r = record
    return extension {
        pkgName = r.pkgName
        name = r.name
        versionName = r.versionName
        versionCode = r.versionCode
        libVersion = r.libVersion
        format =
            when (r.format) {
                ExtensionRecord.Format.JAR -> ExtensionFormat.EXTENSION_FORMAT_JAR
                ExtensionRecord.Format.APK -> ExtensionFormat.EXTENSION_FORMAT_APK
            }
        contentWarning = r.contentWarning.toProto(r.nsfw)
        status = if (installed.loaded) ExtensionStatus.EXTENSION_STATUS_LOADED else ExtensionStatus.EXTENSION_STATUS_ERROR
        installed.loadError?.let { loadError = it }
        installed.sources.orEmpty().forEach { source ->
            sources +=
                extensionSource {
                    id = source.id.toString()
                    name = source.name
                    lang = source.lang
                    available = r.offers(source.lang)
                }
        }
        enabledLangs += r.enabledLangs
        r.signingFingerprint?.let { signingFingerprint = it }
        sha256 = r.sha256
        origin =
            extensionOrigin {
                when (val o = r.origin) {
                    is ExtensionRecord.Origin.Url -> url = o.url
                    is ExtensionRecord.Origin.Upload -> upload = o.fileName
                    is ExtensionRecord.Origin.Store -> storeId = o.storeId
                }
            }
        installedAt = Instant.parse(r.installedAt).toTimestamp()
    }
}

internal fun ExtensionRecord.ContentWarning?.toProto(nsfw: Boolean): ContentWarning =
    when (this) {
        ExtensionRecord.ContentWarning.SAFE -> ContentWarning.CONTENT_WARNING_SAFE
        ExtensionRecord.ContentWarning.MIXED -> ContentWarning.CONTENT_WARNING_MIXED
        ExtensionRecord.ContentWarning.NSFW -> ContentWarning.CONTENT_WARNING_NSFW
        null -> if (nsfw) ContentWarning.CONTENT_WARNING_NSFW else ContentWarning.CONTENT_WARNING_UNSPECIFIED
    }
