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
) {
    companion object {
        const val DEFAULT_PORT = 8902
        const val DEFAULT_MAX_EXTENSION_MB = 50L
        private const val MB = 1024L * 1024

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
            )
        }
    }
}
