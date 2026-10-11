// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

/**
 * The parent of every extension classloader. It exposes the JDK plus the packages that
 * make up the extension host API (the Mihon source API, the Android shims and the
 * libraries extensions are compiled against), and hides everything else on the
 * service's classpath (server, gRPC, protobuf, our own code), so extensions can neither
 * reach into the service nor clash with its dependencies.
 */
class HostClassLoader(
    private val app: ClassLoader,
    private val sharedPrefixes: List<String> = DEFAULT_SHARED,
) : ClassLoader("extension-host", getPlatformClassLoader()) {
    fun isShared(className: String): Boolean = sharedPrefixes.any { className.startsWith(it) }

    override fun findClass(name: String): Class<*> {
        if (!isShared(name)) throw ClassNotFoundException(name)
        return app.loadClass(name)
    }

    override fun findResource(name: String): java.net.URL? = if (isSharedResource(name)) app.getResource(name) else null

    override fun findResources(name: String): java.util.Enumeration<java.net.URL> =
        if (isSharedResource(name)) app.getResources(name) else java.util.Collections.emptyEnumeration()

    private fun isSharedResource(name: String): Boolean = isShared(name.replace('/', '.'))

    companion object {
        /** Package prefixes the host provides to extensions. */
        val DEFAULT_SHARED: List<String> =
            listOf(
                // Mihon source API and helpers extensions compile against.
                "eu.kanade.tachiyomi.",
                // Android and AndroidX shims.
                "android.",
                "androidx.",
                "dalvik.",
                // JS engines.
                "app.cash.quickjs.",
                "com.squareup.duktape.",
                // Libraries extensions expect from the host.
                "kotlin.",
                "kotlinx.coroutines.",
                "kotlinx.serialization.",
                "okhttp3.",
                "okio.",
                "com.squareup.zstd.",
                "org.brotli.",
                "org.jsoup.",
                "rx.",
                "uy.kohesive.injekt.",
                "org.json.",
                "org.intellij.lang.annotations.",
                "org.jetbrains.annotations.",
                // The Android-style date format extension bytecode is rewritten to create.
                "panelarr.mihonsource.compat.IcuSimpleDateFormat",
            )
    }
}
