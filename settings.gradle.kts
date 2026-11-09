// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    includeBuild("build-logic")
    // Maven Central first: it holds the plugins' own artifacts (Kotlin and others). The plugin
    // portal is then only asked for plugin markers and plugins published nowhere else, and isn't
    // in the way of the large downloads, which it serves by redirecting elsewhere.
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // Android build tools (apksig for APK signatures, D8 for test fixtures) live only here.
        google {
            content {
                includeGroup("com.android.tools.build")
                includeGroup("com.android.tools")
            }
        }
        // Node.js for the admin SPA (admin/), downloaded by the node-gradle plugin.
        exclusiveContent {
            forRepository {
                ivy {
                    name = "Node.js"
                    setUrl("https://nodejs.org/dist/")
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "mihon-source"

include(":api", ":android-compat", ":extension-api", ":service", ":admin")
include(":test-extensions:lib14", ":test-extensions:lib16")
include(":tools:compat-check")
