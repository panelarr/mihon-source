// SPDX-License-Identifier: GPL-3.0-or-later

// Generated Java/Kotlin code for the protos in ../proto (the admin API) and
// ../third_party/source-api/proto (the source API, pinned from source-api): messages,
// gRPC stubs and coroutine stubs. Nothing hand-written lives here.
plugins {
    id("mihon.kotlin-jvm")
    alias(libs.plugins.protobuf)
}

dependencies {
    api(libs.protobuf.kotlin)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.coroutines.core)
}

sourceSets {
    main {
        proto {
            srcDir(rootProject.file("proto"))
            srcDir(rootProject.file("third_party/source-api/proto"))
            // Only carries options for the OpenAPI generator.
            exclude("panelarr/mihon/v1/openapi.proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpcKotlin.get()}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("kotlin")
            }
            plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}

// Generated code isn't ours to lint.
tasks.withType<dev.detekt.gradle.Detekt>().configureEach { enabled = false }
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter { exclude { it.file.path.contains("/generated/") } }
}
