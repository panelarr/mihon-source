// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

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
import panelarr.mihonsource.store.AvailableExtension
import panelarr.mihonsource.store.IndexContentWarning
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
            addStoreResponse { store = stores.add(request.url, request.expectedSigningKeyFingerprint.ifBlank { null }).toProto() }
        }

    override suspend fun removeStore(request: RemoveStoreRequest): RemoveStoreResponse =
        rpc {
            stores.remove(request.storeId)
            removeStoreResponse {}
        }

    override suspend fun refreshStore(request: RefreshStoreRequest): RefreshStoreResponse =
        rpc { refreshStoreResponse { store = stores.refresh(request.storeId).toProto() } }

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
        rpc { installFromStoreResponse { extension = stores.install(request.storeId, request.pkgName).toProto() } }

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
