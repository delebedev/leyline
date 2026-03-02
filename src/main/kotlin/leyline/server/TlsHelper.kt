package leyline.server

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Pure-Java TLS helpers — no openssl, keytool, or BouncyCastle.
 *
 * Handles:
 * - PKCS#1 → PKCS#8 key conversion (traefik-certs-dumper outputs PKCS#1)
 * - PEM cert+key → [SSLContext] for JDK HttpsServer
 * - Self-signed cert generation for dev/test
 * - PKCS#1-aware key file normalization for Netty
 */
object TlsHelper {

    // -- Public API ----------------------------------------------------------

    /**
     * Build a JDK [SSLContext] from PEM cert+key files, or self-signed if null.
     * Accepts both PKCS#1 and PKCS#8 key files.
     */
    fun buildJdkSslContext(certFile: File?, keyFile: File?): SSLContext {
        if (certFile != null && keyFile != null && certFile.exists() && keyFile.exists()) {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certFile.inputStream())
            val privateKey = loadPrivateKey(keyFile.readText())
            return buildContext(privateKey, cert)
        }
        return buildSelfSignedContext()
    }

    /**
     * If [keyFile] contains a PKCS#1 key, write a PKCS#8 temp file and return it.
     * Otherwise return the original file unchanged. For Netty's SslContextBuilder.
     */
    fun normalizePkcs1KeyFile(keyFile: File): File {
        val pem = keyFile.readText()
        if (!pem.contains(PKCS1_HEADER)) return keyFile
        val converted = convertPkcs1ToPkcs8(pem)
        val tmp = File.createTempFile("leyline-pkcs8-", ".pem")
        tmp.deleteOnExit()
        tmp.writeText(converted)
        return tmp
    }

    /**
     * Convert PKCS#1 PEM string to PKCS#8 PEM string. Pass-through if already PKCS#8.
     */
    fun convertPkcs1ToPkcs8(pem: String): String {
        if (!pem.contains(PKCS1_HEADER)) return pem
        val base64 = pem
            .replace(PKCS1_HEADER, "").replace(PKCS1_FOOTER, "")
            .replace("\\s".toRegex(), "")
        val pkcs1Bytes = Base64.getDecoder().decode(base64)
        val pkcs8Bytes = wrapPkcs1InPkcs8(pkcs1Bytes)
        return "$PKCS8_HEADER\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs8Bytes) +
            "\n$PKCS8_FOOTER\n"
    }

    // -- Internals -----------------------------------------------------------

    private const val PKCS1_HEADER = "-----BEGIN RSA PRIVATE KEY-----"
    private const val PKCS1_FOOTER = "-----END RSA PRIVATE KEY-----"
    private const val PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----"
    private const val PKCS8_FOOTER = "-----END PRIVATE KEY-----"

    private fun loadPrivateKey(pem: String): java.security.PrivateKey {
        val pkcs8Pem = convertPkcs1ToPkcs8(pem)
        val base64 = pkcs8Pem
            .replace(PKCS8_HEADER, "").replace(PKCS8_FOOTER, "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }

    private fun buildSelfSignedContext(): SSLContext {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certDer = buildSelfSignedCert(kp, "CN=localhost,O=Forge,C=US", 365)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certDer.inputStream())
        return buildContext(kp.private, cert)
    }

    private fun buildContext(
        privateKey: java.security.PrivateKey,
        cert: java.security.cert.Certificate,
    ): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("leyline", privateKey, charArrayOf(), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    // -- PKCS#1 → PKCS#8 ASN.1 wrapping -------------------------------------

    /**
     * PKCS#8 = SEQUENCE { INTEGER 0, AlgorithmIdentifier { rsaEncryption, NULL }, OCTET STRING { pkcs1 } }
     */
    private fun wrapPkcs1InPkcs8(pkcs1: ByteArray): ByteArray {
        val rsaOid = byteArrayOf(
            0x06, 0x09,
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
            0x0D, 0x01, 0x01, 0x01,
        )
        val algId = derSequence(rsaOid + byteArrayOf(0x05, 0x00))
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val keyOctet = derOctetString(pkcs1)
        return derSequence(version + algId + keyOctet)
    }

    // -- Self-signed X.509v1 cert builder ------------------------------------

    /** Build a minimal self-signed X.509v1 cert. Returns DER bytes. RSA + SHA256 only. */
    private fun buildSelfSignedCert(keyPair: KeyPair, dn: String, validityDays: Int): ByteArray {
        val notBefore = Date()
        val notAfter = Calendar.getInstance().apply {
            time = notBefore
            add(Calendar.DAY_OF_YEAR, validityDays)
        }.time

        val tbs = buildTbsCertificate(
            serial = BigInteger.valueOf(System.currentTimeMillis()),
            dn = dn,
            notBefore = notBefore,
            notAfter = notAfter,
            publicKey = keyPair.public.encoded,
        )

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbs)

        return derSequence(tbs + sha256RsaAlgId() + derBitString(sig.sign()))
    }

    private fun buildTbsCertificate(
        serial: BigInteger,
        dn: String,
        notBefore: Date,
        notAfter: Date,
        publicKey: ByteArray,
    ): ByteArray {
        val name = encodeDn(dn)
        val validity = derSequence(derUtcTime(notBefore) + derUtcTime(notAfter))
        return derSequence(
            derInteger(serial) + sha256RsaAlgId() + name + validity + name + publicKey,
        )
    }

    private fun sha256RsaAlgId(): ByteArray {
        val oid = byteArrayOf(
            0x06, 0x09,
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
            0x0D, 0x01, 0x01, 0x0B,
        )
        return derSequence(oid + byteArrayOf(0x05, 0x00))
    }

    // -- ASN.1 DER primitives ------------------------------------------------

    private fun derSequence(content: ByteArray) =
        byteArrayOf(0x30) + derLength(content.size) + content

    private fun derOctetString(content: ByteArray) =
        byteArrayOf(0x04) + derLength(content.size) + content

    private fun derInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(0x02) + derLength(bytes.size) + bytes
    }

    private fun derBitString(content: ByteArray): ByteArray {
        val inner = byteArrayOf(0x00) + content
        return byteArrayOf(0x03) + derLength(inner.size) + inner
    }

    private fun derUtcTime(date: Date): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }
        val s = String.format(
            "%02d%02d%02d%02d%02d%02dZ",
            cal.get(Calendar.YEAR) % 100,
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
        )
        val bytes = s.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x17) + derLength(bytes.size) + bytes
    }

    private fun encodeDn(dn: String): ByteArray {
        var result = byteArrayOf()
        for (rdn in dn.split(",").map { it.trim() }) {
            val (attr, value) = rdn.split("=", limit = 2)
            val oid = when (attr.uppercase()) {
                "CN" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
                "O" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x0A)
                "C" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x06)
                "OU" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x0B)
                "ST" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x08)
                "L" -> byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x07)
                else -> continue
            }
            val valueBytes = value.toByteArray(Charsets.UTF_8)
            val tag = if (attr.uppercase() == "C") 0x13.toByte() else 0x0C.toByte()
            val encoded = byteArrayOf(tag) + derLength(valueBytes.size) + valueBytes
            val atv = derSequence(oid + encoded)
            result += byteArrayOf(0x31) + derLength(atv.size) + atv
        }
        return derSequence(result)
    }

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
        else -> byteArrayOf(
            0x83.toByte(),
            (len shr 16).toByte(),
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte(),
        )
    }
}
