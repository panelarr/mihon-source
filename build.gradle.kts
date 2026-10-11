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
// commit and the SHA-256 of the proto, so a local edit fails the build.
val sourceApiProto = "proto/panelarr/source/v1/source_plugin.proto"

val sourceApiCheck =
    tasks.register("sourceApiCheck") {
        group = "verification"
        description = "Fails when the vendored source API proto differs from its pin."
        val pin = file("third_party/source-api/PIN")
        val proto = file("third_party/source-api/$sourceApiProto")
        inputs.files(pin, proto)
        doLast {
            val want = pin.readLines().first { it.startsWith("sha256=") }.substringAfter("=")
            val got =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(proto.readBytes())
                    .joinToString("") { "%02x".format(it) }
            check(got == want) {
                "third_party/source-api/proto was edited. Change the protos in source-api, then run make sync-source-api."
            }
        }
    }

// make sync-source-api [REF=v0.2.0] [SOURCE_API_DIR=../source-api]
tasks.register("syncSourceApi") {
    group = "panelarr"
    description = "Copies the source API proto from a source-api checkout at a ref and pins it."
    val dir = providers.gradleProperty("sourceApiDir").orElse("../source-api")
    val ref = providers.gradleProperty("sourceApiRef").orElse("main")
    val pin = file("third_party/source-api/PIN")
    val protoPath = sourceApiProto
    val target = file("third_party/source-api/$protoPath")
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
        val commit = String(git("rev-parse", "${ref.get()}^{commit}")).trim()
        val proto = git("show", "$commit:$protoPath")
        val sha256 =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(proto)
                .joinToString("") { "%02x".format(it) }
        target.writeBytes(proto)
        val kept = pin.readLines().filter { it.startsWith("#") || it.startsWith("repository=") }
        pin.writeText((kept + listOf("commit=$commit", "sha256=$sha256")).joinToString("\n", postfix = "\n"))
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
