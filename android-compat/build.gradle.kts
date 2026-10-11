// SPDX-License-Identifier: GPL-3.0-or-later

// Our own replacements for the Android APIs Mihon extensions use, so they run on a plain
// JVM. Written from the public Android API documentation; signatures match Android's so
// extension bytecode links against them.
plugins {
    id("mihon.kotlin-jvm")
}

dependencies {
    implementation(libs.serialization.json)
    // Dispatchers.Main on a plain JVM (MainDispatcherFactory service).
    implementation(libs.coroutines.core)
    // android.text.Html parses with jsoup.
    implementation(libs.jsoup)
    // BitmapFactory: WebP decoding, and CMYK/odd JPEGs the JDK decoder rejects.
    implementation(libs.twelvemonkeys.webp)
    implementation(libs.twelvemonkeys.jpeg)
    // QuickJs and Duktape run on GraalJS. Nothing starts until a script engine is created.
    implementation(libs.graalvm.polyglot)
    implementation(libs.graalvm.js)
    // Android's date formatting is ICU's; extensions rely on its patterns and parsing.
    implementation(libs.icu4j)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}
