package panelarr.mihonsource

import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun `service name is stable`() {
        assertEquals("mihon-source", SERVICE_NAME)
    }
}
