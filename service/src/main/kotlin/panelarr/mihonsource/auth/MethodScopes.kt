// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.auth

import panelarr.mihon.v1.MihonClientsServiceGrpc
import panelarr.source.v1.SourcePluginServiceGrpc

/**
 * The scope each RPC needs. Anything not listed needs [Scopes.ADMIN], so a new RPC is
 * locked down until it's deliberately opened up here.
 */
object MethodScopes {
    /** Calls that need no credentials at all. */
    private val public: Set<String> = setOf(SourcePluginServiceGrpc.getHealthMethod().fullMethodName)

    /** Calls any valid credential may make. */
    private val anyPrincipal: Set<String> = setOf(MihonClientsServiceGrpc.getWhoAmIMethod().fullMethodName)

    private val sourceWrites: Set<String> =
        setOf(
            SourcePluginServiceGrpc.getSetSettingsMethod().fullMethodName,
            SourcePluginServiceGrpc.getSetNetworkProfileMethod().fullMethodName,
        )

    private val sourceService = SourcePluginServiceGrpc.SERVICE_NAME

    sealed interface Requirement {
        data object None : Requirement

        data object Authenticated : Requirement

        data class Scope(
            val scope: String,
        ) : Requirement
    }

    fun requirement(fullMethodName: String): Requirement =
        when {
            fullMethodName in public -> Requirement.None
            fullMethodName in anyPrincipal -> Requirement.Authenticated
            fullMethodName in sourceWrites -> Requirement.Scope(Scopes.SOURCES_WRITE)
            fullMethodName.substringBefore('/') == sourceService -> Requirement.Scope(Scopes.SOURCES_READ)
            else -> Requirement.Scope(Scopes.ADMIN)
        }
}
