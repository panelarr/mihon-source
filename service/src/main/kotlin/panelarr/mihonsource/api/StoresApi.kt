// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

import com.google.rpc.ErrorInfo
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.protobuf.StatusProto
import panelarr.mihon.v1.AddStoreRequest
import panelarr.mihon.v1.AddStoreResponse
import panelarr.mihon.v1.ContentWarning
import panelarr.mihon.v1.InstallFromStoreRequest
import panelarr.mihon.v1.InstallFromStoreResponse
import panelarr.mihon.v1.ListAvailableRequest
import panelarr.mihon.v1.ListAvailableResponse
import panelarr.mihon.v1.ListStoresRequest
import panelarr.mihon.v1.ListStoresResponse
import panelarr.mihon.v1.MihonStoresServiceGrpcKt
import panelarr.mihon.v1.RefreshStoreRequest
import panelarr.mihon.v1.RefreshStoreResponse
import panelarr.mihon.v1.RemoveStoreRequest
import panelarr.mihon.v1.RemoveStoreResponse
import panelarr.mihon.v1.Store
import panelarr.mihon.v1.StoreFormat
import panelarr.mihon.v1.addStoreResponse
import panelarr.mihon.v1.availableExtension
import panelarr.mihon.v1.availableSource
import panelarr.mihon.v1.installFromStoreResponse
import panelarr.mihon.v1.listAvailableResponse
import panelarr.mihon.v1.listStoresResponse
import panelarr.mihon.v1.refreshStoreResponse
import panelarr.mihon.v1.removeStoreResponse
import panelarr.mihon.v1.store
import panelarr.mihonsource.net.Downloader
import panelarr.mihonsource.store.AvailableExtension
import panelarr.mihonsource.store.IndexContentWarning
import panelarr.mihonsource.store.StoreException
import panelarr.mihonsource.store.StoreManager
import panelarr.mihonsource.store.StoreRecord
import java.time.Instant

/** `panelarr.mihon.v1.MihonStoresService`: extension stores. */
class StoresApi(
    private val stores: StoreManager,
) : MihonStoresServiceGrpcKt.MihonStoresServiceCoroutineImplBase() {
    override suspend fun listStores(request: ListStoresRequest): ListStoresResponse =
        rpc { listStoresResponse { stores += this@StoresApi.stores.list().map { it.toProto() } } }

    override suspend fun addStore(request: AddStoreRequest): AddStoreResponse =
        rpc {
            require(request.url.isNotBlank()) { "url is required" }
            storeErrors {
                addStoreResponse {
                    store =
                        stores.add(request.url, request.expectedSigningKeyFingerprint.ifBlank { null }).toProto()
                }
            }
        }

    override suspend fun removeStore(request: RemoveStoreRequest): RemoveStoreResponse =
        rpc {
            stores.remove(request.storeId)
            removeStoreResponse {}
        }

    override suspend fun refreshStore(request: RefreshStoreRequest): RefreshStoreResponse =
        rpc { storeErrors { refreshStoreResponse { store = stores.refresh(request.storeId).toProto() } } }

    override suspend fun listAvailable(request: ListAvailableRequest): ListAvailableResponse =
        rpc {
            val available =
                stores.available(
                    storeId = request.storeId.ifBlank { null },
                    lang = request.lang.ifBlank { null },
                    query = request.query.ifBlank { null },
                    updatesOnly = request.updatesOnly,
                )
            listAvailableResponse { extensions += available.map { it.toProto() } }
        }

    override suspend fun installFromStore(request: InstallFromStoreRequest): InstallFromStoreResponse =
        rpc { storeErrors { installFromStoreResponse { extension = stores.install(request.storeId, request.pkgName).toProto() } } }

    /**
     * Says what failed when a store or a file it lists can't be used, with a
     * google.rpc.ErrorInfo reason next to the usual code: STORE_FETCH_FAILED when a
     * download failed (UNAVAILABLE), STORE_INVALID when the store's content can't be
     * used or its key doesn't match (INVALID_ARGUMENT). Callers can then tell a store
     * problem from this service being unreachable.
     */
    @Suppress("SwallowedException") // The message is carried into the status; the stack trace isn't useful to callers.
    private inline fun <T> storeErrors(block: () -> T): T =
        try {
            block()
        } catch (e: Downloader.DownloadException) {
            throw storeError(Status.Code.UNAVAILABLE, STORE_FETCH_FAILED, e.message)
        } catch (e: StoreException) {
            val fetch = generateSequence<Throwable>(e) { it.cause }.any { it is Downloader.DownloadException }
            throw if (fetch) {
                storeError(Status.Code.UNAVAILABLE, STORE_FETCH_FAILED, e.message)
            } else {
                storeError(Status.Code.INVALID_ARGUMENT, STORE_INVALID, e.message)
            }
        }

    private fun storeError(
        code: Status.Code,
        reason: String,
        message: String?,
    ): StatusException =
        StatusProto.toStatusException(
            com.google.rpc.Status
                .newBuilder()
                .setCode(code.value())
                .setMessage(message ?: reason)
                .addDetails(
                    com.google.protobuf.Any.pack(
                        ErrorInfo
                            .newBuilder()
                            .setReason(reason)
                            .setDomain(DOMAIN)
                            .build(),
                    ),
                ).build(),
        )

    private companion object {
        const val DOMAIN = "mihon.panelarr"
        const val STORE_FETCH_FAILED = "STORE_FETCH_FAILED"
        const val STORE_INVALID = "STORE_INVALID"
    }

    private fun StoreRecord.toProto(): Store {
        val r = this
        return store {
            id = r.id
            url = r.url
            name = r.name
            badgeLabel = r.badgeLabel
            contact = r.contact
            signingKeyFingerprint = r.signingKey
            format = if (r.legacy) StoreFormat.STORE_FORMAT_LEGACY_INDEX else StoreFormat.STORE_FORMAT_EXTENSION_STORE
            addedAt = Instant.parse(r.addedAt).toTimestamp()
            r.refreshedAt?.let { refreshedAt = Instant.parse(it).toTimestamp() }
            r.lastError?.let { lastError = it }
            extensionCount = r.extensionCount
        }
    }

    private fun AvailableExtension.toProto() =
        availableExtension {
            val e = extension
            storeId = this@toProto.storeId
            pkgName = e.pkgName
            name = e.name
            versionName = e.versionName
            versionCode = e.versionCode
            libVersion = e.libVersion
            contentWarning =
                when (e.contentWarning) {
                    IndexContentWarning.CONTENT_WARNING_MIXED -> ContentWarning.CONTENT_WARNING_MIXED
                    IndexContentWarning.CONTENT_WARNING_NSFW -> ContentWarning.CONTENT_WARNING_NSFW
                    else -> ContentWarning.CONTENT_WARNING_SAFE
                }
            e.iconUrl?.let { iconUrl = it }
            sources +=
                e.sources.map { s ->
                    availableSource {
                        id = s.id.toString()
                        name = s.name
                        lang = s.language
                        homeUrl = s.homeUrl
                    }
                }
            supported = this@toProto.supported
            hasJar = e.jarUrl != null
            this@toProto.installed?.record?.let { record ->
                installed = true
                installedVersionName = record.versionName
                installedVersionCode = record.versionCode
            }
            updateAvailable = this@toProto.updateAvailable
        }
}
