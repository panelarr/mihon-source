package panelarr.mihonsource.api

import io.grpc.Status
import panelarr.mihon.v1.Client
import panelarr.mihon.v1.CreateClientRequest
import panelarr.mihon.v1.CreateClientResponse
import panelarr.mihon.v1.DeleteClientRequest
import panelarr.mihon.v1.DeleteClientResponse
import panelarr.mihon.v1.ListClientsRequest
import panelarr.mihon.v1.ListClientsResponse
import panelarr.mihon.v1.MihonClientsServiceGrpcKt
import panelarr.mihon.v1.PrincipalKind
import panelarr.mihon.v1.RotateClientKeyRequest
import panelarr.mihon.v1.RotateClientKeyResponse
import panelarr.mihon.v1.UpdateClientRequest
import panelarr.mihon.v1.UpdateClientResponse
import panelarr.mihon.v1.WhoAmIRequest
import panelarr.mihon.v1.WhoAmIResponse
import panelarr.mihon.v1.client
import panelarr.mihon.v1.createClientResponse
import panelarr.mihon.v1.deleteClientResponse
import panelarr.mihon.v1.listClientsResponse
import panelarr.mihon.v1.rotateClientKeyResponse
import panelarr.mihon.v1.updateClientResponse
import panelarr.mihon.v1.whoAmIResponse
import panelarr.mihonsource.auth.AuthInterceptor
import panelarr.mihonsource.auth.ClientInfo
import panelarr.mihonsource.auth.ClientRegistry
import panelarr.mihonsource.auth.Principal

/** `panelarr.mihon.v1.MihonClientsService`: API clients and their keys. */
class ClientsApi(
    private val registry: ClientRegistry,
) : MihonClientsServiceGrpcKt.MihonClientsServiceCoroutineImplBase() {
    override suspend fun whoAmI(request: WhoAmIRequest): WhoAmIResponse =
        rpc {
            val principal = AuthInterceptor.current() ?: throw Status.UNAUTHENTICATED.asException()
            whoAmIResponse {
                name = principal.name
                scopes += principal.scopes.sorted()
                when (principal) {
                    Principal.Admin -> {
                        kind = PrincipalKind.PRINCIPAL_KIND_ADMIN
                    }

                    is Principal.Client -> {
                        kind = PrincipalKind.PRINCIPAL_KIND_CLIENT
                        clientId = principal.id
                    }
                }
            }
        }

    override suspend fun listClients(request: ListClientsRequest): ListClientsResponse =
        rpc { listClientsResponse { clients += registry.list().map { it.toProto() } } }

    override suspend fun createClient(request: CreateClientRequest): CreateClientResponse =
        rpc {
            val created = registry.create(request.name, request.scopesList.toSet())
            createClientResponse {
                client = created.client.toProto()
                apiKey = created.apiKey
            }
        }

    override suspend fun updateClient(request: UpdateClientRequest): UpdateClientResponse =
        rpc {
            val updated =
                registry.update(
                    id = request.clientId,
                    name = request.name.takeIf { it.isNotBlank() },
                    scopes = request.scopesList.toSet().takeIf { it.isNotEmpty() },
                )
            updateClientResponse { client = updated.toProto() }
        }

    override suspend fun rotateClientKey(request: RotateClientKeyRequest): RotateClientKeyResponse =
        rpc {
            val rotated = registry.rotate(request.clientId)
            rotateClientKeyResponse {
                client = rotated.client.toProto()
                apiKey = rotated.apiKey
            }
        }

    override suspend fun deleteClient(request: DeleteClientRequest): DeleteClientResponse =
        rpc {
            registry.delete(request.clientId)
            deleteClientResponse {}
        }

    private fun ClientInfo.toProto(): Client {
        val info = this
        return client {
            id = info.id
            name = info.name
            scopes += info.scopes.sorted()
            keyPrefix = info.keyPrefix
            createdAt = info.createdAt.toTimestamp()
            info.rotatedAt?.let { rotatedAt = it.toTimestamp() }
            info.lastUsedAt?.let { lastUsedAt = it.toTimestamp() }
        }
    }
}
