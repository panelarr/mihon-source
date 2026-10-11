// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Tells whether an extension drives a WebView, which this service doesn't have. An
 * extension that subclasses WebViewClient runs pages in a browser engine, so most of its
 * calls fail here. A plain reference to WebView isn't enough: many extensions only touch
 * it in optional helpers.
 */
object WebViewUsage {
    private val MARKER = "android/webkit/WebViewClient".toByteArray()

    fun detect(jar: Path): Boolean =
        ZipFile(jar.toFile()).use { zip ->
            zip
                .entries()
                .asSequence()
                .filter { it.name.endsWith(".class") }
                .any { entry -> zip.getInputStream(entry).use { contains(it.readAllBytes(), MARKER) } }
        }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
