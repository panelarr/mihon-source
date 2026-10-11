// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.ResolvableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import panelarr.mihonsource.extension.ExtensionException
import panelarr.mihonsource.extension.ExtensionRecord
import panelarr.mihonsource.extension.HostedSource
import panelarr.mihonsource.extension.SourceInstantiator
import java.nio.file.Path

/** A Mihon source loaded from an extension. */
class MihonSource(
    val source: Source,
    val extension: ExtensionRecord,
    /** Whether the extension's code references WebView (detected when it was loaded). */
    val needsWebView: Boolean = false,
) : HostedSource {
    override val id: Long = source.id
    override val name: String = source.name
    override val lang: String = (source as? CatalogueSource)?.lang ?: ""

    val supportsLatest: Boolean get() = source.supportsLatest
    val configurable: Boolean get() = source is ConfigurableSource
    val resolvable: Boolean get() = source is ResolvableSource
    val homeUrl: String? get() = (source as? HttpSource)?.let { runCatching { it.getHomeUrl() }.getOrNull() }

    override fun toString(): String = "MihonSource($id, $name, $lang)"
}

/** Instantiates the classes an extension's manifest names, like Mihon does. */
class MihonInstantiator : SourceInstantiator {
    override fun instantiate(
        classLoader: ClassLoader,
        record: ExtensionRecord,
        jar: Path,
    ): List<HostedSource> {
        val sources =
            record.classNames.flatMap { className ->
                val type =
                    try {
                        Class.forName(className, false, classLoader)
                    } catch (e: ClassNotFoundException) {
                        throw ExtensionException("class $className not found in the extension", e)
                    }
                when (val instance = type.getDeclaredConstructor().newInstance()) {
                    is Source -> listOf(instance)
                    is SourceFactory -> instance.createSources()
                    else -> throw ExtensionException("$className is neither a Source nor a SourceFactory")
                }
            }
        if (sources.isEmpty()) throw ExtensionException("the extension provides no sources")
        MihonHost.markConstructed(sources)
        val webView = WebViewUsage.detect(jar)
        return sources.map { MihonSource(it, record, webView) }
    }
}
