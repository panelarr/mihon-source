// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.app.Application
import android.content.SharedPreferences
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The host side of the Android shims: where the single [application] keeps its files and
 * preferences.
 *
 * Layout under the data directory given to [configure]:
 * - `prefs/<id>.json` for the preferences named `source_<id>`, the name Mihon uses per source;
 * - `prefs/named/<name>.json` for any other preferences name (sanitised);
 * - `cache/` for `getCacheDir()` and `getExternalCacheDir()`;
 * - `files/` for `getFilesDir()`.
 */
object AndroidHost {
    const val PACKAGE_NAME = "app.mihonsource"

    private val sourcePrefsName = Regex("source_(-?\\d+)")
    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    @Volatile
    private var root: Path? = null

    /** Keyed by file, so two names that map to the same file share one instance. */
    private val preferences = ConcurrentHashMap<Path, JsonSharedPreferences>()

    /** The process-wide Application handed to extensions (through Injekt and as every Context). */
    val application: Application by lazy { HostApplication() }

    /**
     * Called whenever an extension stores a cookie through `android.webkit.CookieManager`, so
     * the host can mirror it into its own cookie jars. See [CookieListener].
     */
    @Volatile
    var cookieListener: CookieListener? = null

    /**
     * Makes the engine behind `android.webkit.WebView`, when the host has a browser. Null
     * (the default) means there's none, and constructing a WebView throws.
     */
    @Volatile
    var webViewProvider: WebViewProvider? = null

    /** Told when a preferences file couldn't be read and was set aside as `keptAs`, so the host can log it. */
    @Volatile
    var damagedPreferencesListener: ((file: Path, keptAs: Path) -> Unit)? = null

    /** Sets the data directory. Calling it again switches directories and drops cached preferences. */
    fun configure(dataDir: Path) {
        synchronized(this) {
            root = dataDir.toAbsolutePath().normalize()
            preferences.clear()
        }
    }

    /** The configured data directory. Throws until [configure] has been called. */
    val dataDir: Path
        get() = root ?: error("AndroidHost.configure(dataDir) has not been called")

    internal fun dir(name: String): Path = Files.createDirectories(dataDir.resolve(name))

    /** The preferences called [name], created on first use and cached. */
    fun sharedPreferences(name: String): SharedPreferences {
        val file = preferencesFile(name)
        return preferences.computeIfAbsent(file) {
            JsonSharedPreferences(it) { damaged, keptAs -> damagedPreferencesListener?.invoke(damaged, keptAs) }
        }
    }

    /** Deletes the preferences file of a source and forgets the cached instance. */
    fun deleteSourcePreferences(sourceId: Long) {
        deletePreferences(sourcePreferencesFile(sourceId))
    }

    internal fun deletePreferences(name: String): Boolean = deletePreferences(preferencesFile(name))

    private fun deletePreferences(file: Path): Boolean {
        synchronized(this) {
            preferences.remove(file)
            return Files.deleteIfExists(file)
        }
    }

    private fun sourcePreferencesFile(sourceId: Long): Path = dataDir.resolve("prefs").resolve("$sourceId.json")

    internal fun preferencesFile(name: String): Path {
        val sourceId =
            sourcePrefsName
                .matchEntire(name)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        if (sourceId != null) return sourcePreferencesFile(sourceId)
        return dataDir.resolve("prefs").resolve("named").resolve("${sanitize(name)}.json")
    }

    /**
     * Turns a preferences name into a safe file name. Names that need changing get a short
     * hash suffix, so different names never share a file.
     */
    private fun sanitize(name: String): String {
        val safe = unsafeChars.replace(name, "_")
        if (safe == name && name.isNotEmpty() && !name.startsWith(".")) return name
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        val suffix = digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
        return "${safe.trimStart('.').take(MAX_NAME)}-$suffix"
    }

    private const val HASH_BYTES = 4
    private const val MAX_NAME = 100
}

/** Receives the cookies extensions store through `android.webkit.CookieManager`. */
fun interface CookieListener {
    /**
     * [url] is the URL given to `setCookie`; [setCookie] is the cookie as given, in
     * `Set-Cookie` header syntax (`name=value; Path=/; ...`).
     */
    fun onCookieSet(
        url: String,
        setCookie: String,
    )

    /** All cookies were removed. */
    fun onCookiesCleared() {}
}
