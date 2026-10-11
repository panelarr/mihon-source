// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("mihon.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

val testExtensions: Configuration =
    configurations.create("testExtensions") {
        isCanBeConsumed = false
        isTransitive = false
    }

dependencies {
    implementation(project(":api"))
    implementation(project(":extension-api"))
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
    implementation(libs.micrometer.prometheus)
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
    testImplementation(project(":tools:compat-check"))

    testExtensions(project(":test-extensions:lib14"))
    testExtensions(project(":test-extensions:lib16"))
}

application {
    mainClass = "panelarr.mihonsource.MainKt"
    applicationName = "mihon-source"
}

tasks.named<JavaExec>("run") {
    // `make run` keeps its state in ./data at the repository root and serves the SPA from admin/dist.
    dependsOn(":admin:buildSpa")
    environment("MIHON_DATA_DIR", rootProject.file("data").path)
    environment("MIHON_ADMIN_DIR", rootProject.file("admin/dist").path)
    workingDir = rootProject.projectDir
}

// The built jar carries the admin SPA under admin/. Tests don't need it.
tasks.named<Jar>("jar") {
    dependsOn(":admin:buildSpa")
    from(rootProject.file("admin/dist")) { into("admin") }
}

tasks.named<Test>("test") {
    // Test extensions are fixtures loaded by the extension loader, not test classpath entries.
    inputs.files(testExtensions)
    dependsOn(testExtensions)
    val jars = files(testExtensions)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dtest.extensions=" + jars.files.joinToString(File.pathSeparator)) }
    systemProperty("java.awt.headless", "true")
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
