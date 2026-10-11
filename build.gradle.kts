// SPDX-License-Identifier: GPL-3.0-or-later

// Root project: modules apply the conventions from build-logic/. The lifecycle tasks below
// are what the Makefile calls, so `make <target>` and `./gradlew <target>` behave the same.
plugins {
    id("mihon.buf")
}

val kotlinModules = subprojects.map { it.path }

tasks.register("setup") {
    group = "panelarr"
    description = "Resolves every dependency, the JDK toolchain and buf."
    dependsOn(kotlinModules.map { "$it:dependencies" }, "bufBinary")
}

tasks.register("lint") {
    group = "panelarr"
    description = "Runs detekt, the ktlint format check and buf lint."
    dependsOn(kotlinModules.flatMap { listOf("$it:detekt", "$it:ktlintCheck") }, "bufLint", "bufFormatCheck")
}

tasks.register("fmt") {
    group = "panelarr"
    description = "Formats Kotlin sources with ktlint and protos with buf."
    dependsOn(kotlinModules.map { "$it:ktlintFormat" }, "bufFormat")
}

tasks.register("check") {
    group = "verification"
    description = "Lint plus tests."
    dependsOn("lint", kotlinModules.map { "$it:check" })
}

tasks.register("gen") {
    group = "panelarr"
    description = "Regenerates generated sources (the OpenAPI document)."
    dependsOn("bufGenerate")
}

tasks.register("docker") {
    group = "panelarr"
    description = "Builds the Docker image."
    doLast { logger.lifecycle("The Docker image isn't set up yet.") }
}
