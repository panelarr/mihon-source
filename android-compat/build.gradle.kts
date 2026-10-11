// SPDX-License-Identifier: GPL-3.0-or-later

// Our own replacements for the Android APIs Mihon extensions use, so they run on a plain
// JVM. Written from the public Android API documentation; signatures match Android's so
// extension bytecode links against them.
plugins {
    id("mihon.kotlin-jvm")
}

dependencies {
    implementation(libs.serialization.json)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}
