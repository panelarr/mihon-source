// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.secret

import panelarr.mihonsource.storage.DurableFiles
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts secrets that must be stored but can't be hashed because the service needs them
 * back (proxy passwords). AES-256-GCM with a key from `MIHON_SECRET_KEY` (base64, 32
 * bytes) or, when unset, a key generated on first start in `secret.key` next to the data.
 *
 * Encrypted values look like `enc:v1:<base64 of nonce + ciphertext>`.
 */
class SecretBox private constructor(
    private val key: SecretKeySpec,
) {
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val sealed = nonce + cipher.doFinal(plain.toByteArray())
        return PREFIX + Base64.getEncoder().encodeToString(sealed)
    }

    fun decrypt(sealed: String): String {
        require(sealed.startsWith(PREFIX)) { "not an encrypted value" }
        val bytes = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
        return String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES))
    }

    companion object {
        private const val PREFIX = "enc:v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32

        fun load(
            keyFile: Path,
            fromEnv: String?,
        ): SecretBox {
            val bytes =
                when {
                    fromEnv != null -> Base64.getDecoder().decode(fromEnv)
                    Files.exists(keyFile) -> Base64.getDecoder().decode(Files.readString(keyFile).trim())
                    else -> generate(keyFile)
                }
            require(bytes.size == KEY_BYTES) { "the secret key must be $KEY_BYTES bytes (base64-encoded)" }
            return SecretBox(SecretKeySpec(bytes, "AES"))
        }

        private fun generate(keyFile: Path): ByteArray {
            val bytes = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
            DurableFiles.write(keyFile, Base64.getEncoder().encode(bytes), private = true)
            return bytes
        }
    }
}
