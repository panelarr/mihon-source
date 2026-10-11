// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.network

import android.content.Context
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs JavaScript for extensions, on the host's QuickJS implementation. */
class JavaScriptEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> evaluate(script: String): T =
        withContext(Dispatchers.Default) {
            QuickJs.create().use { it.evaluate(script) as T }
        }
}
