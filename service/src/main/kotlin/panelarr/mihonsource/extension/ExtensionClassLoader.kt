// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import java.io.Closeable
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.util.Collections
import java.util.Enumeration
import java.util.jar.JarFile

/**
 * Loads one extension's jar. Host packages always come from [host], so an extension
 * can't replace the API it's loaded through. Everything else is looked up in the
 * extension's own jar first (child first), so the libraries an extension bundles win
 * over anything the host happens to have.
 *
 * Class bytes pass through [transform] before they're defined, for bytecode fix-ups.
 */
class ExtensionClassLoader(
    jar: Path,
    private val host: HostClassLoader,
    name: String,
    private val transform: (className: String, bytes: ByteArray) -> ByteArray = { _, bytes -> bytes },
) : ClassLoader(name, host),
    Closeable {
    private val jarFile = JarFile(jar.toFile(), false)
    private val jarUri: URI = jar.toUri()

    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            val loaded =
                findLoadedClass(name)
                    ?: if (name.startsWith("java.") || host.isShared(name)) {
                        host.loadClass(name)
                    } else {
                        findOwnClass(name) ?: host.loadClass(name)
                    }
            if (resolve) resolveClass(loaded)
            loaded
        }

    override fun findClass(name: String): Class<*> = findOwnClass(name) ?: throw ClassNotFoundException(name)

    private fun findOwnClass(name: String): Class<*>? {
        val entry = jarFile.getJarEntry(name.replace('.', '/') + ".class") ?: return null
        val bytes = transform(name, jarFile.getInputStream(entry).use { it.readAllBytes() })
        return defineClass(name, bytes, 0, bytes.size)
    }

    override fun getResource(name: String): URL? = findResource(name) ?: host.getResource(name)

    override fun getResources(name: String): Enumeration<URL> {
        val own = listOfNotNull(findResource(name))
        val parent = host.getResources(name).toList()
        return Collections.enumeration(own + parent)
    }

    override fun findResource(name: String): URL? {
        val entry = jarFile.getJarEntry(name.removePrefix("/")) ?: return null
        return URI.create("jar:$jarUri!/${entry.name}").toURL()
    }

    override fun close() {
        jarFile.close()
    }
}
