// SPDX-License-Identifier: GPL-3.0-or-later

// compat-check: installs extensions from a store into a running mihon-source, optionally
// runs source checks, and reports what works. See docs/compat-check.md.
plugins {
    id("mihon.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

dependencies {
    implementation(project(":api"))
    implementation(platform(libs.armeria.bom))
    implementation(libs.armeria.grpc)
    implementation(libs.armeria.grpc.kotlin)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    runtimeOnly(libs.logback)
}

application {
    mainClass = "panelarr.mihonsource.compat.check.MainKt"
    applicationName = "compat-check"
}

tasks.named<JavaExec>("run") {
    // Reports land in build/compat-reports/ at the repository root, which git ignores.
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
