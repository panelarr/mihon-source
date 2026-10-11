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
) {
    companion object {
        const val DEFAULT_PORT = 8080

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun value(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

            val port = value("MIHON_PORT")?.let { raw -> raw.toIntOrNull() ?: error("MIHON_PORT must be a number, got '$raw'") }
            return AppConfig(
                dataDir = Path.of(value("MIHON_DATA_DIR") ?: "/data").toAbsolutePath().normalize(),
                host = value("MIHON_HOST") ?: "0.0.0.0",
                port = port ?: DEFAULT_PORT,
                adminToken = value("MIHON_ADMIN_TOKEN"),
            )
        }
    }
}
