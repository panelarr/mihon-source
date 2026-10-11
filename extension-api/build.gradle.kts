// SPDX-License-Identifier: GPL-3.0-or-later

// The Mihon source API that extensions are compiled against (eu.kanade.tachiyomi.*), written
// to be binary compatible with extensions-lib 1.4 and 1.6, plus the libraries extensions
// expect the host to provide. Everything here is visible to extensions.
plugins {
    id("mihon.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":android-compat"))
    api(libs.okhttp)
    api(libs.okhttp.brotli)
    api(libs.okhttp.zstd)
    api(libs.zstd.okio)
    api(libs.jsoup)
    api(libs.rxjava)
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.serialization.json.okio)
    api(libs.serialization.protobuf)
    api(libs.org.json)
    api(libs.injekt.core) {
        // Old logging and test libraries leak from its POM.
        exclude(group = "ch.qos.logback")
        exclude(group = "junit")
        exclude(group = "org.slf4j")
    }
}

kotlin {
    compilerOptions {
        // Extensions call Kotlin's interface default methods both directly and through the
        // DefaultImpls classes older compilers emitted, so emit both.
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE
        // Extensions run Java 11 bytecode; the API itself only needs to be loadable on 21.
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

// This is a compatibility surface with Android's naming (snake_case properties, `GET`).
tasks.withType<dev.detekt.gradle.Detekt>().configureEach { enabled = false }
