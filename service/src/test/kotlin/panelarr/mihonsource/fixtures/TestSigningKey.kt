// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.fixtures

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/** A throwaway signing key and self-signed certificate for test APKs and jars. */
class TestSigningKey(
    commonName: String = "mihon-source test",
) {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()

    val certificate: X509Certificate =
        run {
            val name = X500Name("CN=$commonName")
            val now = Instant.now()
            val builder =
                JcaX509v3CertificateBuilder(
                    name,
                    BigInteger.valueOf(now.toEpochMilli()),
                    Date.from(now.minus(1, ChronoUnit.DAYS)),
                    Date.from(now.plus(VALID_DAYS, ChronoUnit.DAYS)),
                    name,
                    keyPair.public,
                )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }

    private companion object {
        const val KEY_BITS = 2048
        const val VALID_DAYS = 3650L
    }
}
