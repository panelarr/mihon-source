// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
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
    }
}

rootProject.name = "mihon-source"

include(":api", ":android-compat", ":service")
