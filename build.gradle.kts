// SPDX-License-Identifier: GPL-3.0-or-later

// Root project: modules apply the conventions from build-logic/. The lifecycle tasks below
// are what the Makefile calls, so `make <target>` and `./gradlew <target>` behave the same.
plugins {
    id("mihon.buf")
}

val kotlinModules = subprojects.filter { it.buildFile.exists() }.map { it.path } - ":admin"

tasks.register("setup") {
    group = "panelarr"
    description = "Resolves every dependency, the JDK toolchain and buf."
    dependsOn(kotlinModules.map { "$it:dependencies" }, "bufBinary", ":admin:npmInstall")
}

// panelarr.source.v1 is pinned from source-api: third_party/source-api/PIN names the
// commit and the SHA-256 of each proto file, so a local edit fails the build.
val sourceApiProtos =
    listOf(
        "proto/panelarr/jobs/v1/jobs.proto",
        "proto/panelarr/source/v1/source_plugin.proto",
    )

val sourceApiCheck =
    tasks.register("sourceApiCheck") {
        group = "verification"
        description = "Fails when the vendored source API protos differ from their pin."
        val pin = file("third_party/source-api/PIN")
        val protos = sourceApiProtos.associateWith { file("third_party/source-api/$it") }
        inputs.files(pin, protos.values)
        doLast {
            fun sha256Hex(bytes: ByteArray): String =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            val pinned =
                pin
                    .readLines()
                    .filter { it.startsWith("sha256 ") }
                    .associate { line -> line.removePrefix("sha256 ").substringBefore("=") to line.substringAfter("=") }
            check(pinned.keys == protos.keys) {
                "third_party/source-api/PIN doesn't list the vendored protos. Run make sync-source-api."
            }
            protos.forEach { (path, proto) ->
                check(proto.isFile && sha256Hex(proto.readBytes()) == pinned[path]) {
                    "third_party/source-api/$path was edited. Change the protos in source-api, then run make sync-source-api."
                }
            }
        }
    }

// make sync-source-api [REF=v0.2.0] [SOURCE_API_DIR=../source-api]
tasks.register("syncSourceApi") {
    group = "panelarr"
    description = "Copies the source API protos from a source-api checkout at a ref and pins them."
    val dir = providers.gradleProperty("sourceApiDir").orElse("../source-api")
    val ref = providers.gradleProperty("sourceApiRef").orElse("main")
    val pin = file("third_party/source-api/PIN")
    val protoPaths = sourceApiProtos
    val targetDir = file("third_party/source-api")
    doLast {
        fun git(vararg args: String): ByteArray {
            val process =
                ProcessBuilder(listOf("git", "-C", dir.get()) + args)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
            val out = process.inputStream.readBytes()
            check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed in ${dir.get()}" }
            return out
        }
        fun sha256Hex(bytes: ByteArray): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        val commit = String(git("rev-parse", "${ref.get()}^{commit}")).trim()
        val hashes =
            protoPaths.map { path ->
                val proto = git("show", "$commit:$path")
                targetDir.resolve(path).apply { parentFile.mkdirs() }.writeBytes(proto)
                "sha256 $path=${sha256Hex(proto)}"
            }
        val kept = pin.readLines().filter { it.startsWith("#") || it.startsWith("repository=") }
        pin.writeText((kept + "commit=$commit" + hashes).joinToString("\n", postfix = "\n"))
        logger.lifecycle("Pinned source-api $commit. Run make gen and make check.")
    }
}

tasks.register("lint") {
    group = "panelarr"
    description = "Runs detekt, the ktlint format check, buf lint, the source API pin check and the admin SPA's lint."
    dependsOn(kotlinModules.flatMap { listOf("$it:detekt", "$it:ktlintCheck") }, "bufLint", "bufFormatCheck", sourceApiCheck)
    dependsOn(":admin:lint")
}

tasks.register("fmt") {
    group = "panelarr"
    description = "Formats Kotlin sources with ktlint, protos with buf and the admin SPA with Biome."
    dependsOn(kotlinModules.map { "$it:ktlintFormat" }, "bufFormat", ":admin:fmt")
}

tasks.register("check") {
    group = "verification"
    description = "Lint plus tests."
    dependsOn("lint", kotlinModules.map { "$it:check" }, ":admin:check")
}

tasks.register("gen") {
    group = "panelarr"
    description = "Regenerates generated sources (the OpenAPI document and the admin UI's API types)."
    dependsOn("bufGenerate", ":admin:genApi")
}

// `make docker` builds the image for this machine's platform and loads it as
// panelarr/mihon-source:dev. Multi-arch images are built and pushed by CI.
tasks.register<Exec>("docker") {
    group = "panelarr"
    description = "Builds the Docker image (panelarr/mihon-source:dev) for the local platform."
    val tag = providers.gradleProperty("dockerTag").orElse("panelarr/mihon-source:dev")
    commandLine("docker", "buildx", "build", "--load", "--tag", tag.get(), ".")
    workingDir = projectDir
}
