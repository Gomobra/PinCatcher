package com.pincatcher.core.capture.tls

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * Mints the certificates the MITM presents (PRD 6.1, "Generate leaf cert").
 *
 * ## Why one CA and many leaves
 *
 * The user installs exactly one certificate. Every host gets a leaf signed by it,
 * minted on demand and cached. Anything else means asking someone to install a
 * new certificate per host they visit, which nobody will do.
 *
 * ## Why this is in `:core`
 *
 * Pure JVM, nothing from Android, so it is unit-tested: a leaf is checked to
 * actually verify under the CA's key, to carry the name as a SAN, and to be
 * refused a name it was not asked for. Certificate bugs are otherwise only
 * visible as an opaque handshake failure on a device, which is the worst place
 * to find them.
 *
 * ## What this CA is not
 *
 * It is not in the Android Keystore and not hardware-backed — it is a software
 * key held by this app, which is what lets it sign without asking the user each
 * time. That is a genuine trade-off, and it is why exporting [caPem] is a
 * deliberate, warned-about action rather than something that happens quietly.
 */
class CertificateAuthority(
    /** PEM of the CA certificate, for installation by the user. */
    val caPem: String,
    val caCertificate: X509Certificate,
    private val caKey: PrivateKey,
) {

    /** One host's certificate, plus the private key that goes with it. */
    class Leaf(
        val certificate: X509Certificate,
        val privateKey: PrivateKey,
        val pem: String,
    )

    private val leaves = LinkedHashMap<String, Leaf>()

    /** Signed by the same CA, so a client that trusts the CA trusts every leaf. */
    @Synchronized
    fun leafFor(host: String): Leaf = leaves.getOrPut(normalize(host)) { mint(host) }

    @Synchronized
    fun cachedLeafCount(): Int = leaves.size

    /** Drops cached leaves. Their keys go with them; the CA does not. */
    @Synchronized
    fun forgetAll() = leaves.clear()

    /** The chain a client needs to reach a trusted anchor: leaf, then the CA. */
    fun chainFor(leaf: Leaf): Array<X509Certificate> = arrayOf(leaf.certificate, caCertificate)

    private fun mint(host: String): Leaf {
        val name = normalize(host)
        require(name.isNotEmpty()) { "a leaf needs a name to put in a certificate" }

        val keyPair = generateKeyPair()
        val now = System.currentTimeMillis()
        val serial = nextSerial()
        val subject = X500Name("CN=$name")

        val builder = JcaX509v3CertificateBuilder(
            caCertificate,
            serial,
            // Backdated by an hour: a client whose clock runs a few minutes behind
            // would otherwise reject the certificate as not-yet-valid, which reads
            // as an attack rather than as a clock difference.
            Date(now - CLOCK_SKEW_MS),
            Date(now + LEAF_VALIDITY_MS),
            subject,
            keyPair.public,
        )

        val utilities = JcaX509ExtensionUtils()
        // CA:false. A leaf that claims it can sign certificates is one that could
        // sign another CA.
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        // serverAuth. Without it some clients reject the leaf as lacking an appropriate
            // certificate purpose even though the name matches.
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.getInstance(OID_EKU_SERVER_AUTH)),
            )
        // The requested name as a SAN. Modern clients ignore the CN when verifying
        // hostnames, so a leaf without this fails with a name mismatch however
        // correct its subject is.
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, name)),
        )
        builder.addExtension(Extension.subjectKeyIdentifier, false, utilities.createSubjectKeyIdentifier(keyPair.public))
        builder.addExtension(Extension.authorityKeyIdentifier, false, utilities.createAuthorityKeyIdentifier(caCertificate))

        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER).build(caKey)
        val certificate = JcaX509CertificateConverter()
            .setProvider(PROVIDER)
            .getCertificate(builder.build(signer))
        certificate.checkValidity()
        return Leaf(certificate, keyPair.private, toPem(certificate))
    }

    private fun normalize(host: String) = host.trim().lowercase()

    companion object {
        private val PROVIDER = BouncyCastleProvider()
        private val SERIAL = AtomicLong(System.currentTimeMillis())

        private const val CLOCK_SKEW_MS = 60L * 60L * 1000L
        private const val LEAF_VALIDITY_MS = 7L * 24L * 60L * 60L * 1000L
        private const val KEY_BITS = 2048
        const val DEFAULT_CA_DAYS = 3650

        private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
        private const val PEM_END = "-----END CERTIFICATE-----"
        private const val PEM_LINE_WIDTH = 64

        private val OID_EKU_SERVER_AUTH = ASN1ObjectIdentifier("1.3.6.1.5.5.7.3.1")

        init {
            // Registered once: the JcaX509* builders resolve the provider by name,
            // and installing it twice would leave two to choose between.
            if (Security.getProvider("BC") == null) Security.addProvider(PROVIDER)
        }

        private fun nextSerial(): BigInteger =
            BigInteger.valueOf(SERIAL.incrementAndGet()).max(BigInteger.ONE)

        private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA", PROVIDER).apply {
            initialize(KEY_BITS, SecureRandom())
        }.generateKeyPair()

        /**
         * DER encoded, then base64, then wrapped at 64 columns.
         *
         * The base64 step is the whole point: a PEM file is base64 text, and
         * writing the raw DER bytes into the armour produces a file every parser
         * rejects, which is the failure mode at the exact moment the user is trying
         * to install the certificate.
         */
        private fun toPem(certificate: X509Certificate): String =
            Base64.getEncoder().encodeToString(certificate.encoded)
                .chunked(PEM_LINE_WIDTH)
                .joinToString("\n", "$PEM_BEGIN\n", "\n$PEM_END\n")

        /** A fresh CA, ready to be exported for the user to install. */
        fun generate(
            commonName: String = "PinCatcher Root CA",
            validityDays: Int = DEFAULT_CA_DAYS,
        ): CertificateAuthority {
            val keyPair = generateKeyPair()
            val name = X500Name("CN=$commonName")
            val now = System.currentTimeMillis()

            val builder = JcaX509v3CertificateBuilder(
                name,
                nextSerial(),
                Date(now - CLOCK_SKEW_MS),
                Date(now + validityDays * 24L * 60L * 60L * 1000L),
                name,
                keyPair.public,
            )
            // CA:true with a path length of 0 — this key signs leaves only and must
            // never be able to sign another CA.
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
            )
            val utilities = JcaX509ExtensionUtils()
            builder.addExtension(Extension.subjectKeyIdentifier, false, utilities.createSubjectKeyIdentifier(keyPair.public))

            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER).build(keyPair.private)
            val certificate = JcaX509CertificateConverter()
                .setProvider(PROVIDER)
                .getCertificate(builder.build(signer))
            certificate.checkValidity()
            return CertificateAuthority(toPem(certificate), certificate, keyPair.private)
        }

        /**
         * The CA as a KeyStore, which is what Android's certificate installer
         * takes and what several TLS clients expect rather than a bare PEM.
         */
        fun toKeyStore(ca: CertificateAuthority, password: CharArray): KeyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, password)
                setCertificateEntry("pin-catcher-root", ca.caCertificate)
            }

        /** Reads a PEM back into a certificate. Used to reload a saved CA and by tests. */
        fun parsePem(pem: String): X509Certificate {
            require(pem.contains(PEM_BEGIN) && pem.contains(PEM_END)) { "not a PEM certificate: no armour" }
            val body = pem.substringAfter(PEM_BEGIN).substringBefore(PEM_END).filterNot { it.isWhitespace() }
            require(body.isNotEmpty()) { "not a PEM certificate: empty body" }
            val der = Base64.getDecoder().decode(body)
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
        }
    }
}