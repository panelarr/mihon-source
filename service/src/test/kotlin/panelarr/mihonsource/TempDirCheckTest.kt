// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TempDirCheckTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a normal directory runs code, and leaves no probe behind`() {
        assertTrue(runsCode(dir))
        assertTrue(dir.toFile().list().isNullOrEmpty())
    }

    @Test
    fun `a directory that isn't there doesn't`() {
        assertFalse(runsCode(dir.resolve("missing")))
    }
}
