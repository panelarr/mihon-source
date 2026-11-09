// SPDX-License-Identifier: GPL-3.0-or-later

// Maven Central first, for the same reason as in the root settings: the Kotlin compiler and the
// Gradle plugins' artifacts are there, and the plugin portal only serves them through a redirect.
// The portal stays for what is published only there (the `kotlin-dsl` plugin, ktlint-gradle).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
