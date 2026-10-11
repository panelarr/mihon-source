// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** A checked extension file, converted to a jar in a temporary location, ready to install. */
class PreparedExtension(
    val descriptor: ExtensionDescriptor,
    val format: ExtensionRecord.Format,
    val jar: Path,
    val sha256: String,
    val signingFingerprint: String?,
)

/** What the caller expects the file to be. Mismatches abort the install. */
data class Expectations(
    val sha256: String? = null,
    val signingFingerprint: String? = null,
)

/**
 * Checks an extension file and prepares it for loading: tells a jar from an APK by its
 * contents, verifies the signature, reads the extension's metadata, checks the
 * extensions-lib version and the caller's expectations, and converts an APK to a jar.
 */
class ExtensionInspector(
    private val workDir: Path,
    private val jarDescriptors: JarDescriptorReader = JarDescriptorReader(),
) {
    /**
     * [file] is consumed: on success the prepared jar replaces it (APK) or is it (jar).
     * [hint] carries metadata from a store index, used for jars that don't describe
     * themselves.
     */
    fun prepare(
        file: Path,
        expectations: Expectations,
        hint: ExtensionDescriptor? = null,
    ): PreparedExtension {
        val sha256 = Signatures.sha256Hex(Files.readAllBytes(file))
        expectations.sha256?.let { expected ->
            if (Signatures.normalize(expected) != sha256) {
                throw ExtensionException("SHA-256 mismatch: expected ${Signatures.normalize(expected)}, got $sha256")
            }
        }
        val prepared =
            when (kindOf(file)) {
                Kind.APK -> prepareApk(file, sha256)
                Kind.JAR -> prepareJar(file, sha256, hint)
            }
        expectations.signingFingerprint?.let { expected ->
            val actual = prepared.signingFingerprint ?: "none (unsigned)"
            if (Signatures.normalize(expected) != prepared.signingFingerprint) {
                Files.deleteIfExists(prepared.jar)
                throw ExtensionException("signing fingerprint mismatch: expected ${Signatures.normalize(expected)}, got $actual")
            }
        }
        return prepared
    }

    private enum class Kind { APK, JAR }

    private fun kindOf(file: Path): Kind =
        try {
            ZipFile(file.toFile()).use { zip ->
                val names =
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toList()
                when {
                    "AndroidManifest.xml" in names && names.any { it.matches(DEX_ENTRY) } -> Kind.APK
                    names.any { it.endsWith(".class") } -> Kind.JAR
                    else -> throw ExtensionException("the file is neither an extension APK nor a jar")
                }
            }
        } catch (e: ZipException) {
            throw ExtensionException("the file isn't a jar or APK (${e.message})", e)
        }

    private fun prepareApk(
        apk: Path,
        sha256: String,
    ): PreparedExtension {
        val fingerprint = Signatures.verifyApk(apk)
        val manifest =
            ZipFile(apk.toFile()).use { zip ->
                zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { ApkManifest.parse(it.readAllBytes()) }
            }
        val descriptor = ManifestDescriptors.fromManifest(manifest)
        LibVersion.requireSupported(descriptor.libVersion)
        val jar = Files.createTempFile(workDir, descriptor.pkgName, ".jar")
        try {
            ApkConverter.convert(apk, jar)
        } catch (e: ExtensionException) {
            Files.deleteIfExists(jar)
            throw e
        } finally {
            Files.deleteIfExists(apk)
        }
        return PreparedExtension(descriptor, ExtensionRecord.Format.APK, jar, sha256, fingerprint)
    }

    private fun prepareJar(
        jar: Path,
        sha256: String,
        hint: ExtensionDescriptor?,
    ): PreparedExtension {
        val fingerprint = Signatures.verifyJar(jar)
        val descriptor = jarDescriptors.read(jar, hint)
        LibVersion.requireSupported(descriptor.libVersion)
        return PreparedExtension(descriptor, ExtensionRecord.Format.JAR, jar, sha256, fingerprint)
    }

    private companion object {
        val DEX_ENTRY = Regex("classes\\d*\\.dex")
    }
}

/** Turns manifest data into an [ExtensionDescriptor], the way Mihon reads extensions. */
object ManifestDescriptors {
    const val FEATURE = "tachiyomi.extension"
    const val META_CLASS = "tachiyomi.extension.class"
    const val META_NSFW = "tachiyomi.extension.nsfw"
    const val META_NAME = "tachiyomix.name"
    const val META_CONTENT_WARNING = "tachiyomix.contentWarning"
    const val META_LIB = "tachiyomix.extensionLib"
    private const val LABEL_PREFIX = "Tachiyomi: "
    private val LIB_PATTERN = Regex("\\d+\\.\\d+")

    fun fromManifest(manifest: ApkManifest): ExtensionDescriptor {
        if (FEATURE !in manifest.features) throw ExtensionException("not a Mihon extension (no '$FEATURE' feature)")
        return fromMetaData(
            pkgName = manifest.packageName,
            label = manifest.label,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            metaData = manifest.metaData,
        )
    }

    fun fromMetaData(
        pkgName: String,
        label: String?,
        versionName: String,
        versionCode: Long,
        metaData: Map<String, String>,
    ): ExtensionDescriptor {
        val classes = metaData[META_CLASS]?.takeIf { it.isNotBlank() } ?: throw ExtensionException("no '$META_CLASS' entry")
        val warning =
            when (metaData[META_CONTENT_WARNING]?.trim()) {
                "0" -> ExtensionRecord.ContentWarning.SAFE
                "1" -> ExtensionRecord.ContentWarning.MIXED
                "2" -> ExtensionRecord.ContentWarning.NSFW
                else -> null
            }
        val lib =
            metaData[META_LIB]?.trim()?.takeIf { it.matches(LIB_PATTERN) }
                ?: runCatching { LibVersion.fromVersionName(versionName) }.getOrElse { throw ExtensionException(it.message ?: "") }
        return ExtensionDescriptor(
            pkgName = PackageNames.require(pkgName),
            name =
                metaData[META_NAME]?.takeIf { it.isNotBlank() }
                    ?: label?.removePrefix(LABEL_PREFIX)?.takeIf { it.isNotBlank() }
                    ?: pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = lib,
            classNames = resolveClassNames(pkgName, classes),
            nsfw = warning == ExtensionRecord.ContentWarning.NSFW || (warning == null && metaData[META_NSFW] == "1"),
            contentWarning = warning,
        )
    }

    /** `.Foo;com.example.Bar` with package `com.example` gives `com.example.Foo`, `com.example.Bar`. */
    fun resolveClassNames(
        pkgName: String,
        value: String,
    ): List<String> =
        value
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith('.')) pkgName + it else it }
}

/** Package names double as directory names, so they're checked strictly. */
object PackageNames {
    private val VALID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    fun require(pkgName: String): String {
        if (!VALID.matches(pkgName) || pkgName.length > MAX_LENGTH) throw ExtensionException("invalid package name '$pkgName'")
        return pkgName
    }

    private const val MAX_LENGTH = 200
}
