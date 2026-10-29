// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.slf4j.LoggerFactory
import panelarr.mihonsource.compat.AndroidHost
import panelarr.mihonsource.extensionapi.ExtensionHost
import panelarr.mihonsource.network.SourceNetworks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.nio.file.Path
import java.util.Collections
import java.util.WeakHashMap

/**
 * Sets up what extensions expect from their host: the Android application context and
 * its files, the objects extensions look up through Injekt, and a network helper per
 * HTTP source.
 */
object MihonHost {
    /** Sources whose construction finished; only their ids are safe to read. */
    private val constructed: MutableSet<Source> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private val log = LoggerFactory.getLogger(MihonHost::class.java)

    @OptIn(ExperimentalSerializationApi::class)
    fun install(
        dataDir: Path,
        networks: SourceNetworks,
    ) {
        AndroidHost.configure(dataDir)
        AndroidHost.damagedPreferencesListener = { file, keptAs ->
            log.error("Unreadable source settings {}; kept it as {} and continuing without them", file, keptAs.fileName)
        }
        ExtensionHost.install { source -> NetworkHelper(networks.create { idOf(source) }) }

        val application = AndroidHost.application
        Injekt.addSingleton<Application>(application)
        Injekt.addSingleton<Context>(application)
        Injekt.addSingleton(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
        Injekt.addSingleton<ProtoBuf>(ProtoBuf)
        Injekt.addSingleton(NetworkHelper(networks.global))
        Injekt.addSingleton(JavaScriptEngine(application))
    }

    /** Marks sources as fully constructed, so their network can use their id. */
    fun markConstructed(sources: Collection<Source>) {
        constructed.addAll(sources)
    }

    /**
     * The id of [source], or null while it's still being constructed. Reading an id too
     * early could cache a wrong one (it's computed from properties the subclass may not
     * have set yet), so requests made from constructors use the default profile.
     */
    private fun idOf(source: HttpSource): Long? = if (source in constructed) source.id else null
}
