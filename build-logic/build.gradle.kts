// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.kotlin)
    implementation(libs.gradle.detekt)
    implementation(libs.gradle.ktlint)
    implementation(libs.gradle.serialization)
    // Makes the generated `libs` catalog accessors usable from the convention plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
