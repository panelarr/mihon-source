// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.config

import java.nio.file.Path

/**
 * Process-level settings, read from environment variables at start. Everything that can
 * change at runtime (stores, profiles, clients) lives in files under [dataDir] instead.
 */
data class AppConfig(
    val dataDir: Path,
    val host: String,
    val port: Int,
    /** Admin token from the environment. Null means use (or generate) the stored one. */
    val adminToken: String?,
    /** Largest extension file accepted (download or upload), in bytes. */
    val maxExtensionBytes: Long = DEFAULT_MAX_EXTENSION_MB * MB,
    /** Base64 AES-256 key for secrets at rest. Null means use (or generate) `secret.key`. */
    val secretKey: String? = null,
    /** Directory with a built admin SPA, overriding the one bundled in the jar (development). */
    val adminDir: Path? = null,
    /** Port for unauthenticated GET /metrics on its own listener. Null means /metrics only on the API port, with a token. */
    val metricsPort: Int? = null,
    /** How often each source gets a health check. Null means no scheduled checks. */
    val healthCheckInterval: java.time.Duration? = null,
    /** How often store indexes are refreshed. Zero means never (refresh by hand only). */
    val storeRefreshInterval: java.time.Duration = DEFAULT_STORE_REFRESH,
    /** Result cache lifetimes per kind of result; zero turns caching off for that kind. */
    val cacheTtls: Map<String, java.time.Duration> = DEFAULT_CACHE_TTLS,
    val cacheMaxBytes: Long = DEFAULT_CACHE_MB * MB,
) {
    companion object {
        const val DEFAULT_PORT = 8902
        const val DEFAULT_MAX_EXTENSION_MB = 50L
        private const val MB = 1024L * 1024

        val DEFAULT_STORE_REFRESH: java.time.Duration = java.time.Duration.ofHours(12)
        const val DEFAULT_CACHE_MB = 64L

        /** Lists change often, pages rarely. Keys match ResultCache.Kind names. */
        val DEFAULT_CACHE_TTLS: Map<String, java.time.Duration> =
            mapOf(
                "list" to java.time.Duration.ofMinutes(5),
                "details" to java.time.Duration.ofMinutes(10),
                "pages" to java.time.Duration.ofMinutes(30),
                "filters" to java.time.Duration.ofHours(1),
            )
        private val SIMPLE_DURATION = Regex("(\\d+)\\s*(s|m|h|d)")

        /** "90s", "30m", "6h", "1d", "0" or ISO-8601 ("PT6H"). */
        fun duration(text: String): java.time.Duration {
            val trimmed = text.trim().lowercase()
            if (trimmed == "0" || trimmed == "off") return java.time.Duration.ZERO
            SIMPLE_DURATION.matchEntire(trimmed)?.let { match ->
                val amount = match.groupValues[1].toLong()
                return when (match.groupValues[2]) {
                    "s" -> java.time.Duration.ofSeconds(amount)
                    "m" -> java.time.Duration.ofMinutes(amount)
                    "h" -> java.time.Duration.ofHours(amount)
                    else -> java.time.Duration.ofDays(amount)
                }
            }
            return runCatching { java.time.Duration.parse(text.trim().uppercase()) }
                .getOrElse { error("'$text' isn't a duration (e.g. 30m, 6h, 1d)") }
        }

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            // NAME_FILE (a path, e.g. a Docker secret) is read when NAME itself isn't set.
            fun value(name: String): String? =
                env[name]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: env["${name}_FILE"]?.trim()?.takeIf { it.isNotEmpty() }?.let { file ->
                        java.nio.file.Files
                            .readString(Path.of(file))
                            .trim()
                            .takeIf { it.isNotEmpty() }
                    }

            fun number(name: String): Long? = value(name)?.let { raw -> raw.toLongOrNull() ?: error("$name must be a number, got '$raw'") }

            val port = number("MIHON_PORT")?.toInt()
            return AppConfig(
                dataDir = Path.of(value("MIHON_DATA_DIR") ?: "/data").toAbsolutePath().normalize(),
                host = value("MIHON_HOST") ?: "0.0.0.0",
                port = port ?: DEFAULT_PORT,
                adminToken = value("MIHON_ADMIN_TOKEN"),
                maxExtensionBytes = (number("MIHON_MAX_EXTENSION_MB") ?: DEFAULT_MAX_EXTENSION_MB) * MB,
                secretKey = value("MIHON_SECRET_KEY"),
                adminDir = value("MIHON_ADMIN_DIR")?.let(Path::of),
                metricsPort = number("MIHON_METRICS_PORT")?.toInt(),
                healthCheckInterval = value("MIHON_HEALTH_CHECK_INTERVAL")?.let(::duration)?.takeIf { !it.isZero },
                storeRefreshInterval = value("MIHON_STORE_REFRESH_INTERVAL")?.let(::duration) ?: DEFAULT_STORE_REFRESH,
                cacheTtls =
                    DEFAULT_CACHE_TTLS.mapValues { (kind, default) ->
                        value("MIHON_CACHE_TTL_${kind.uppercase()}")?.let(::duration) ?: default
                    },
                cacheMaxBytes = (number("MIHON_CACHE_MAX_MB") ?: DEFAULT_CACHE_MB) * MB,
            )
        }
    }
}
