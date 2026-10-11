package panelarr.mihonsource.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * Generation and hashing of tokens and API keys.
 *
 * Tokens are 256 bits from a CSPRNG, so a plain SHA-256 is enough to store them: unlike
 * passwords they can't be guessed, and a slow hash would only slow down every request.
 */
object Secrets {
    private const val TOKEN_BYTES = 32
    private val random = SecureRandom()

    fun randomToken(prefix: String): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun randomId(bytes: Int = 8): String = HexFormat.of().formatHex(ByteArray(bytes).also(random::nextBytes))

    fun hash(token: String): String = HexFormat.of().formatHex(sha256(token))

    /** Compares a token with a stored hash in constant time. */
    fun matches(
        token: String,
        storedHash: String,
    ): Boolean {
        val expected = runCatching { HexFormat.of().parseHex(storedHash) }.getOrNull() ?: return false
        return MessageDigest.isEqual(sha256(token), expected)
    }

    private fun sha256(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
}
