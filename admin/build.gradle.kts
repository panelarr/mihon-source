// SPDX-License-Identifier: GPL-3.0-or-later

// The admin SPA: Vite + React + TypeScript, built, linted and tested through npm. The
// node-gradle plugin downloads Node.js itself, so a JDK is all a contributor needs.
// The static build lands in admin/dist, which the service serves at `/`.
import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
    alias(libs.plugins.node)
}

node {
    download = true
    version = libs.versions.nodejs.get()
    npmInstallCommand = "ci"
    // The Node.js repository is declared in settings.gradle.kts (project repositories are
    // not allowed in this build).
    distBaseUrl = null
}

val openApiDocument = rootProject.layout.projectDirectory.file("service/src/main/resources/openapi/openapi.yaml")
val stamps = layout.buildDirectory.dir("stamps")

// Everything the npm scripts read. Declared so Gradle can skip tasks that are up to date.
fun NpmTask.sources() {
    dependsOn(tasks.npmInstall)
    inputs.files(
        "package.json",
        "package-lock.json",
        "tsconfig.json",
        "vite.config.ts",
        "biome.json",
        "index.html",
        ".env.mock",
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("scripts").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("public").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Runs an npm script that produces no files: a stamp file marks it as done.
fun registerScript(
    name: String,
    script: String,
    taskDescription: String,
    configure: NpmTask.() -> Unit = {},
) = tasks.register<NpmTask>(name) {
    group = "verification"
    description = taskDescription
    npmCommand = listOf("run", script)
    sources()
    configure()
    val stamp = stamps.map { it.file(name) }
    outputs.file(stamp)
    doLast { stamp.get().asFile.writeText("ok\n") }
}

val biome = registerScript("biome", "lint", "Lints and checks the formatting of the SPA with Biome.")
val typecheck = registerScript("typecheck", "typecheck", "Type-checks the SPA with tsc.")
val apiClientCheck =
    registerScript("apiClientCheck", "gen:api:check", "Fails when the generated API types are older than the OpenAPI document.") {
        inputs.file(openApiDocument).withPathSensitivity(PathSensitivity.RELATIVE)
    }
val test = registerScript("test", "test", "Runs the SPA's unit tests with Vitest.")

tasks.register("lint") {
    group = "verification"
    description = "Biome, tsc and the generated API client check."
    dependsOn(biome, typecheck, apiClientCheck)
}

tasks.register<NpmTask>("fmt") {
    group = "panelarr"
    description = "Formats the SPA and applies safe lint fixes with Biome."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "fmt")
}

tasks.register<NpmTask>("genApi") {
    group = "panelarr"
    description = "Regenerates the typed API client from the OpenAPI document."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "gen:api")
}

val buildSpa =
    tasks.register<NpmTask>("buildSpa") {
        group = "build"
        description = "Builds the static SPA into admin/dist."
        npmCommand = listOf("run", "build")
        sources()
        outputs.dir("dist")
    }

tasks.assemble { dependsOn(buildSpa) }
tasks.check { dependsOn("lint", test) }
tasks.clean { delete("dist") }

tasks.register<NpmTask>("dev") {
    group = "application"
    description = "Runs the Vite dev server against a backend (MIHON_BACKEND, default http://localhost:8902)."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "dev")
}

tasks.register<NpmTask>("devMock") {
    group = "application"
    description = "Runs the Vite dev server with mocked API responses (no backend needed)."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "dev:mock")
}
