package com.pincatcher.core.capture.tls

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import java.security.Security
import java.security.SignatureException
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate

/**
 * [CertificateAuthority] tests.
 *
 * Every assertion is one a TLS client would make, checked the way a client makes
 * it. A certificate bug otherwise surfaces as an opaque handshake failure on a
 * device, long after the code that caused it was written.
 *
 * RSA key generation is slow, so one CA is shared. It is immutable after
 * construction, so the tests do not interfere with each other.
 */
class CertificateAuthorityTest {

    private val ca = CertificateAuthority.generate(commonName = "PinCatcher Test CA")

    @Test
    fun `the ca is self signed and can sign`() {
        val certificate = CertificateAuthority.parsePem(ca.caPem)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertTrue(certificate.basicConstraints >= 0, "a CA must be able to sign leaves")
        certificate.checkValidity()
    }

    @Test
    fun `a leaf verifies under the ca key`() {
        // The assertion that matters most. If it fails, every handshake through the
        // MITM fails with "unable to find valid certification path to requested
        // target", which says nothing at all about the cause.
        ca.leafFor("verify.example.com").certificate.verify(ca.caCertificate.publicKey)
    }

    @Test
    fun `a leaf is issued by the ca`() {
        // Combined with verify(), this is what a client checks when it walks the
        // chain: the leaf's issuer has to be the CA that is trusted.
        val leaf = ca.leafFor("path.example.com")
        assertEquals(ca.caCertificate.subjectX500Principal, leaf.certificate.issuerX500Principal)
    }

    @Test
    fun `the chain hands over leaf first then the ca`() {
        // Order matters: a client walks the chain from the leaf to an anchor.
        val chain = ca.chainFor(ca.leafFor("order.example.com"))
        assertEquals(2, chain.size)
        assertEquals(chain[1].subjectX500Principal, chain[0].issuerX500Principal)
    }

    @Test
    fun `the name is carried as a subject alternative name`() {
        // Modern clients ignore the CN when verifying hostnames. A leaf without a
        // SAN fails with a name mismatch however correct its subject looks.
        val leaf = ca.leafFor("san.example.com")
        val names = leaf.certificate.subjectAlternativeNames.orEmpty().map { it[1] as String }
        assertTrue(
            names.any { it.equals("san.example.com", ignoreCase = true) },
            "expected the SAN to carry the name, got $names",
        )
    }

    @Test
    fun `a leaf cannot sign certificates`() {
        assertEquals(
            -1,
            ca.leafFor("notaca.example.com").certificate.basicConstraints,
            "a leaf that can sign is a leaf that could sign another CA",
        )
    }

    @Test
    fun `a leaf carries the server auth purpose`() {
        val purposes = ca.leafFor("eku.example.com").certificate.extendedKeyUsage.orEmpty()
        assertTrue(purposes.contains("1.3.6.1.5.5.7.3.1"), "expected serverAuth, got $purposes")
    }

    @Test
    fun `minting is cached per host`() {
        val before = ca.cachedLeafCount()
        val first = ca.leafFor("cached.example.com")
        val second = ca.leafFor("cached.example.com")
        assertEquals(first.pem, second.pem)
        assertEquals(before + 1, ca.cachedLeafCount(), "a second lookup must not mint again")
    }

    @Test
    fun `host case does not produce a second certificate`() {
        // Certificates match case-insensitively, so two spellings of one host must
        // not become two keys to keep and two entries in a cache.
        assertEquals(ca.leafFor("case.example.com").pem, ca.leafFor("CASE.example.com").pem)
        assertEquals(ca.leafFor("case.example.com").pem, ca.leafFor("Case.Example.COM").pem)
    }

    @Test
    fun `different hosts get different certificates`() {
        assertNotEquals(ca.leafFor("one.example.com").pem, ca.leafFor("two.example.com").pem)
    }

    @Test
    fun `a leaf is valid now and lasts about a week`() {
        val leaf = ca.leafFor("dates.example.com")
        leaf.certificate.checkValidity()
        val days = (leaf.certificate.notAfter.time - leaf.certificate.notBefore.time) / (24L * 60 * 60 * 1000)
        assertTrue(days in 7..8, "expected roughly a week, got $days days")
    }

    @Test
    fun `a leaf is backdated so a slow clock still accepts it`() {
        // A client whose clock runs behind would otherwise see "not yet valid",
        // which reads as an attack rather than as a clock difference.
        val leaf = ca.leafFor("clock.example.com")
        assertTrue(
            leaf.certificate.notBefore.time < System.currentTimeMillis(),
            "notBefore is in the future",
        )
    }

    @Test
    fun `a blank host is refused`() {
        // The name ends up in a certificate. Refusing early beats emitting one with
        // an empty subject, which is the kind of thing that gets signed and shipped.
        assertThrows(IllegalArgumentException::class.java) { ca.leafFor("   ") }
    }

    @Test
    fun `the ca survives a pem round trip`() {
        // The PEM is what the user installs. If it does not parse back, the flow
        // breaks exactly where the user can least debug it.
        val parsed = CertificateAuthority.parsePem(ca.caPem)
        assertEquals(ca.caCertificate.subjectX500Principal, parsed.subjectX500Principal)
        assertEquals(ca.caCertificate.publicKey, parsed.publicKey)
        assertEquals("RSA", parsed.publicKey.algorithm.uppercase())
    }

    @Test
    fun `a certificate from another ca does not verify under ours`() {
        // The other direction, which is what makes the CA a boundary at all.
        val foreign = CertificateAuthority.generate(commonName = "Someone Else")
            .leafFor("api.example.com")
        assertThrows(SignatureException::class.java) {
            foreign.certificate.verify(ca.caCertificate.publicKey)
        }
    }

    @Test
    fun `the ca can be handed over as a keystore`() {
        val store = CertificateAuthority.toKeyStore(ca, "unused".toCharArray())
        val loaded = store.getCertificate("pin-catcher-root") as X509Certificate
        assertEquals(ca.caCertificate.subjectX500Principal, loaded.subjectX500Principal)
    }

    @Test
    fun `a saved ca can be read back as a trust anchor`() {
        // What the Android installer does with the exported file.
        val anchor = TrustAnchor(CertificateAuthority.parsePem(ca.caPem), null)
        assertEquals(ca.caCertificate.subjectX500Principal, anchor.trustedCert.subjectX500Principal)
    }

    @Test
    fun `bouncy castle is installed once`() {
        // Registered in a companion initialiser. Registering per instance would
        // leave several providers to choose between.
        assertEquals(1, Security.getProviders().count { it.name == "BC" })
    }

    @Test
    fun `the pem is the shape an installer accepts`() {
        val lines = ca.caPem.trim().lines()
        assertEquals("-----BEGIN CERTIFICATE-----", lines.first(), "missing header")
        assertEquals("-----END CERTIFICATE-----", lines.last(), "missing footer")
        assertTrue(lines.drop(1).dropLast(1).all { it.length <= 64 }, "body lines must be short enough")
        // Base64, not raw DER. Raw DER in the armour is a file every parser rejects,
        // which fails at the moment the user is trying to install the certificate.
        assertTrue(
            lines.drop(1).dropLast(1).joinToString("").all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' },
            "the body must be base64",
        )
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(CertificateAuthority.parsePem(ca.caPem).encoded.inputStream())
        assertEquals(ca.caCertificate, certificate)
    }
}