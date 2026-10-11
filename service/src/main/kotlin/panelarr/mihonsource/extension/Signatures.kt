// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import com.android.apksig.ApkVerifier
import java.nio.file.Path
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.HexFormat
import java.util.jar.JarFile

/** Signature checks for extension files. Fingerprints are lowercase hex SHA-256 of the signing certificate. */
object Signatures {
    private const val MIN_CHECKED_PLATFORM = 24

    fun sha256Hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun fingerprint(certificate: Certificate): String = sha256Hex(certificate.encoded)

    /** Normalizes a user-supplied hex digest: lowercase, without colons or spaces. */
    fun normalize(hex: String): String = hex.filterNot { it == ':' || it.isWhitespace() }.lowercase()

    /**
     * Verifies an APK's signature (all schemes apksig knows) and returns the fingerprint of
     * its signer. Fails when the APK isn't signed or the signature doesn't verify.
     */
    fun verifyApk(apk: Path): String {
        // Extensions target modern Android; checking for ancient platform versions would
        // reject valid SHA-256 signatures that those versions couldn't verify.
        val result =
            ApkVerifier
                .Builder(apk.toFile())
                .setMinCheckedPlatformVersion(MIN_CHECKED_PLATFORM)
                .build()
                .verify()
        if (!result.isVerified) {
            val errors = result.allErrors.joinToString("; ").ifEmpty { "unknown error" }
            throw ExtensionException("the APK signature doesn't verify: $errors")
        }
        val signers = result.signerCertificates
        if (signers.size != 1) throw ExtensionException("the APK must have exactly one signer, found ${signers.size}")
        return fingerprint(signers.single())
    }

    /**
     * Returns the fingerprint of a signed jar's signer, or null when the jar isn't signed.
     * Fails when it's signed but some class entries are unsigned, tampered with or signed
     * by someone else.
     */
    fun verifyJar(jar: Path): String? {
        JarFile(jar.toFile(), true).use { file ->
            val signers = mutableSetOf<String>()
            var unsigned = 0
            for (entry in file.entries()) {
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                // Reading the entry fully makes JarFile check its digest; a mismatch throws.
                file.getInputStream(entry).use { it.readAllBytes() }
                val certificates = entry.codeSigners?.map { it.signerCertPath.certificates.first() }
                if (certificates.isNullOrEmpty()) unsigned++ else certificates.forEach { signers += fingerprint(it) }
            }
            return when {
                signers.isEmpty() -> null
                unsigned > 0 -> throw ExtensionException("the jar is signed, but $unsigned of its entries aren't")
                signers.size > 1 -> throw ExtensionException("the jar has more than one signer")
                else -> signers.single()
            }
        }
    }
}
