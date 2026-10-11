// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

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
import panelarr.mihon.v1.UninstallExtensionRequest
import panelarr.mihon.v1.UninstallExtensionResponse
import panelarr.mihon.v1.extension
import panelarr.mihon.v1.extensionOrigin
import panelarr.mihon.v1.extensionSource
import panelarr.mihon.v1.getExtensionResponse
import panelarr.mihon.v1.installExtensionResponse
import panelarr.mihon.v1.listExtensionsResponse
import panelarr.mihon.v1.reloadExtensionResponse
import panelarr.mihon.v1.uninstallExtensionResponse
import panelarr.mihonsource.extension.Expectations
import panelarr.mihonsource.extension.ExtensionInstaller
import panelarr.mihonsource.extension.ExtensionManager
import panelarr.mihonsource.extension.ExtensionRecord
import panelarr.mihonsource.extension.InstalledExtension
import java.time.Instant

/** `panelarr.mihon.v1.MihonAdminService`: installed extensions. */
class ExtensionsApi(
    private val manager: ExtensionManager,
    private val installer: ExtensionInstaller,
) : MihonAdminServiceGrpcKt.MihonAdminServiceCoroutineImplBase() {
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

    override suspend fun reloadExtension(request: ReloadExtensionRequest): ReloadExtensionResponse =
        rpc { reloadExtensionResponse { extension = manager.reload(request.pkgName).toProto() } }
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
                }
        }
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
