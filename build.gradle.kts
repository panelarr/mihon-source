// Root project: modules apply the conventions from build-logic/. The lifecycle tasks below
// are what the Makefile calls, so `make <target>` and `./gradlew <target>` behave the same.

val kotlinModules = subprojects.map { it.path }

tasks.register("setup") {
    group = "panelarr"
    description = "Resolves every dependency and the JDK toolchain."
    dependsOn(kotlinModules.map { "$it:dependencies" })
}

tasks.register("lint") {
    group = "panelarr"
    description = "Runs detekt and the ktlint format check."
    dependsOn(kotlinModules.flatMap { listOf("$it:detekt", "$it:ktlintCheck") })
}

tasks.register("fmt") {
    group = "panelarr"
    description = "Formats Kotlin sources with ktlint."
    dependsOn(kotlinModules.map { "$it:ktlintFormat" })
}

tasks.register("gen") {
    group = "panelarr"
    description = "Regenerates generated sources."
}

tasks.register("docker") {
    group = "panelarr"
    description = "Builds the Docker image."
    doLast { logger.lifecycle("The Docker image isn't set up yet.") }
}
