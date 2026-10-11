// SPDX-License-Identifier: GPL-3.0-or-later

// A test extension built the way real extensions are: Java 11 bytecode against the host
// API (compile only), packaged as a jar with a plain-text AndroidManifest.xml.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("mihon.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        // Plain classes instead of invokedynamic, so D8 can turn the jar into dex for APK tests.
        freeCompilerArgs.addAll("-Xjdk-release=11", "-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline")
    }
}

tasks.withType<JavaCompile>().configureEach { options.release = 11 }

dependencies {
    "compileOnly"(project(":extension-api"))
}

tasks.named<Jar>("jar") {
    from("src/main/manifest") { include("AndroidManifest.xml") }
}

// Test extensions are fixtures; they aren't held to the production lint rules.
tasks.withType<dev.detekt.gradle.Detekt>().configureEach { enabled = false }
