package panelarr.mihonsource

import java.util.Properties

/** Facts about this build, written by Gradle into build-info.properties. */
object BuildInfo {
    private val properties =
        Properties().apply {
            BuildInfo::class.java.getResourceAsStream("build-info.properties")?.use { load(it) }
        }

    val version: String = properties.getProperty("version", "unknown")
    val swaggerUiVersion: String = properties.getProperty("swaggerUiVersion", "")
}
