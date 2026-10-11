// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.auth

import panelarr.mihon.v1.MihonAdminServiceGrpc
import panelarr.mihon.v1.MihonClientsServiceGrpc
import panelarr.source.v1.SourcePluginServiceGrpc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopesTest {
    @Test
    fun `admin allows everything`() {
        Scopes.all.forEach { assertTrue(Scopes.allows(setOf(Scopes.ADMIN), it)) }
    }

    @Test
    fun `write implies read but not admin`() {
        assertTrue(Scopes.allows(setOf(Scopes.SOURCES_WRITE), Scopes.SOURCES_READ))
        assertFalse(Scopes.allows(setOf(Scopes.SOURCES_WRITE), Scopes.ADMIN))
        assertFalse(Scopes.allows(setOf(Scopes.SOURCES_READ), Scopes.SOURCES_WRITE))
    }

    @Test
    fun `methods map to the expected requirements`() {
        fun req(name: String) = MethodScopes.requirement(name)
        assertEquals(MethodScopes.Requirement.None, req(SourcePluginServiceGrpc.getHealthMethod().fullMethodName))
        assertEquals(MethodScopes.Requirement.Authenticated, req(MihonClientsServiceGrpc.getWhoAmIMethod().fullMethodName))
        assertEquals(
            MethodScopes.Requirement.Scope(Scopes.SOURCES_READ),
            req(SourcePluginServiceGrpc.getListSourcesMethod().fullMethodName),
        )
        assertEquals(
            MethodScopes.Requirement.Scope(Scopes.SOURCES_WRITE),
            req(SourcePluginServiceGrpc.getSetSettingsMethod().fullMethodName),
        )
        assertEquals(
            MethodScopes.Requirement.Scope(Scopes.ADMIN),
            req(MihonAdminServiceGrpc.getListExtensionsMethod().fullMethodName),
        )
        assertEquals(MethodScopes.Requirement.Scope(Scopes.ADMIN), req("some.unknown.Service/Method"))
    }
}
