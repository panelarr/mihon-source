// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

import panelarr.mihonsource.BuildInfo
import panelarr.mihonsource.SERVICE_NAME
import panelarr.source.v1.GetInfoRequest
import panelarr.source.v1.GetInfoResponse
import panelarr.source.v1.HealthRequest
import panelarr.source.v1.HealthResponse
import panelarr.source.v1.HealthStatus
import panelarr.source.v1.ListSourcesRequest
import panelarr.source.v1.ListSourcesResponse
import panelarr.source.v1.PluginCapability
import panelarr.source.v1.SourcePluginServiceGrpcKt
import panelarr.source.v1.getInfoResponse
import panelarr.source.v1.healthResponse
import panelarr.source.v1.listSourcesResponse
import panelarr.source.v1.pluginInfo

/** `panelarr.source.v1`: the source API. */
class SourcePluginApi(
    private val catalog: SourceCatalog,
) : SourcePluginServiceGrpcKt.SourcePluginServiceCoroutineImplBase() {
    override suspend fun getInfo(request: GetInfoRequest): GetInfoResponse =
        getInfoResponse {
            info =
                pluginInfo {
                    id = SERVICE_NAME
                    name = SERVICE_NAME
                    version = BuildInfo.version
                    contracts += listOf(CONTRACT_SOURCE, CONTRACT_MIHON)
                    capabilities += PluginCapability.PLUGIN_CAPABILITY_DYNAMIC_SOURCES
                }
        }

    override suspend fun health(request: HealthRequest): HealthResponse = healthResponse { status = HealthStatus.HEALTH_STATUS_SERVING }

    override suspend fun listSources(request: ListSourcesRequest): ListSourcesResponse =
        rpc {
            listSourcesResponse {
                sources += catalog.sources().filter { request.lang.isEmpty() || it.lang == request.lang }
            }
        }

    companion object {
        const val CONTRACT_SOURCE = "panelarr.source.v1"
        const val CONTRACT_MIHON = "panelarr.mihon.v1"
    }
}
