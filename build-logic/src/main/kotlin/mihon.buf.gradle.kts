// buf tasks for the protos in proto/: lint, format and OpenAPI generation. The buf CLI
// comes from Maven Central for the current OS and architecture, so nothing needs to be
// installed by hand.
import org.gradle.accessors.dm.LibrariesForLibs

val libs = the<LibrariesForLibs>()

val bufCli: Configuration = configurations.create("bufCli")

dependencies {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart =
        when {
            os.contains("mac") -> "osx"
            os.contains("win") -> "windows"
            else -> "linux"
        }
    val archPart = if (arch == "aarch64" || arch == "arm64") "aarch_64" else "x86_64"
    add(bufCli.name, "build.buf:buf:${libs.versions.buf.get()}:$osPart-$archPart@exe")
}

val bufBinary =
    tasks.register<Sync>("bufBinary") {
        description = "Copies the buf CLI into the build directory and makes it executable."
        from(bufCli)
        into(layout.buildDirectory.dir("buf"))
        rename { "buf" }
        filePermissions { unix("rwxr-xr-x") }
    }

val bufExecutable = layout.buildDirectory.file("buf/buf")
val bufInputs = files("buf.yaml", "buf.lock", "buf.gen.yaml", fileTree("proto") { include("**/*.proto") })

fun bufTask(
    name: String,
    description: String,
    vararg arguments: String,
) = tasks.register<Exec>(name) {
    group = "panelarr"
    this.description = description
    dependsOn(bufBinary)
    inputs.files(bufInputs)
    executable = bufExecutable.get().asFile.path
    args(*arguments)
    workingDir = projectDir
}

bufTask("bufLint", "Lints the protos with buf.", "lint")
bufTask("bufFormatCheck", "Checks proto formatting with buf.", "format", "--diff", "--exit-code")
bufTask("bufFormat", "Formats the protos with buf.", "format", "--write")
bufTask("bufGenerate", "Generates the OpenAPI document from the protos.", "generate")
bufTask("bufDepUpdate", "Updates buf.lock.", "dep", "update")
