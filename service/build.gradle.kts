// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("mihon.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

dependencies {
    implementation(project(":api"))
    implementation(platform(libs.armeria.bom))
    implementation(libs.armeria.core)
    implementation(libs.armeria.grpc)
    implementation(libs.armeria.grpc.kotlin)
    implementation(libs.armeria.kotlin)
    implementation(libs.protobuf.java.util)
    implementation(libs.grpc.services)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.logback)
    implementation(libs.okhttp)
    implementation(libs.apksig)
    implementation(libs.dex2jar)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    runtimeOnly(libs.swagger.ui)

    testImplementation(libs.armeria.junit5)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.r8)
    testImplementation(libs.bouncycastle.pkix)
}

application {
    mainClass = "panelarr.mihonsource.MainKt"
    applicationName = "mihon-source"
}

tasks.named<JavaExec>("run") {
    // `make run` keeps its state in ./data at the repository root.
    environment("MIHON_DATA_DIR", rootProject.file("data").path)
    workingDir = rootProject.projectDir
}

val buildInfo =
    tasks.register<WriteProperties>("buildInfo") {
        destinationFile = layout.buildDirectory.file("generated/build-info/panelarr/mihonsource/build-info.properties")
        property("version", project.version.toString())
        property("swaggerUiVersion", libs.versions.swaggerUi.get())
    }

sourceSets.main {
    resources.srcDir(buildInfo.map { layout.buildDirectory.dir("generated/build-info").get() })
}
