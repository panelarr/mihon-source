// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi

/** What extensions see as the app. */
@Suppress("FunctionOnlyReturningConstant")
object AppInfo {
    fun getVersionCode(): Int = VERSION_CODE

    fun getVersionName(): String = "1.0.0"

    fun getSupportedImageMimeTypes(): List<String> = listOf("image/jpeg", "image/png", "image/gif", "image/webp")

    private const val VERSION_CODE = 1
}
