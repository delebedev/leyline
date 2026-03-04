package leyline.infra

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * TLS helpers using BouncyCastle — no openssl, keytool, or hand-rolled ASN.1.
 *
 * Handles:
 * - PKCS#1 → PKCS#8 key conversion (traefik-certs-dumper outputs PKCS#1)
 * - PEM cert+key → [javax.net.ssl.SSLContext] for JDK HttpsServer
 * - Self-signed cert generation for dev/test
 * - PKCS#1-aware key file normalization for Netty
 */
object TlsHelper {

    private val converter = JcaPEMKeyConverter()

    /**
     * Build a JDK [javax.net.ssl.SSLContext] from PEM cert+key files, or self-signed if null.
     * Accepts both PKCS#1 and PKCS#8 key files.
     */
    fun buildJdkSslContext(certFile: File?, keyFile: File?): SSLContext {
        if (certFile != null && keyFile != null && certFile.exists() && keyFile.exists()) {
            val chain = loadCertificateChain(certFile)
            val key = loadPrivateKey(keyFile.readText())
            return buildContext(key, chain)
        }
        return buildSelfSignedContext()
    }

    /**
     * If [keyFile] contains a PKCS#1 key, write a PKCS#8 temp file and return it.
     * Otherwise return the original file unchanged. For Netty's SslContextBuilder.
     */
    fun normalizePkcs1KeyFile(keyFile: File): File {
        val pem = keyFile.readText()
        if (!pem.contains("BEGIN RSA PRIVATE KEY")) return keyFile
        val converted = convertPkcs1ToPkcs8(pem)
        val tmp = File.createTempFile("leyline-pkcs8-", ".pem")
        tmp.deleteOnExit()
        // Restrict permissions — private key material
        tmp.setReadable(false, false)
        tmp.setReadable(true, true)
        tmp.writeText(converted)
        return tmp
    }

    /**
     * Convert PKCS#1 PEM string to PKCS#8 PEM string. Pass-through if already PKCS#8.
     */
    fun convertPkcs1ToPkcs8(pem: String): String {
        if (!pem.contains("BEGIN RSA PRIVATE KEY")) return pem
        val key = loadPrivateKey(pem)
        // key.encoded is PKCS#8 (JCA standard). Write as PRIVATE KEY PEM.
        val sw = StringWriter()
        PemWriter(sw).use { it.writeObject(PemObject("PRIVATE KEY", key.encoded)) }
        return sw.toString()
    }

    // -- Internals -----------------------------------------------------------

    private fun loadPrivateKey(pem: String): PrivateKey {
        val obj = PEMParser(StringReader(pem)).use { it.readObject() }
        return when (obj) {
            is PEMKeyPair -> converter.getKeyPair(obj).private
            is PrivateKeyInfo -> converter.getPrivateKey(obj)
            else -> error("Unexpected PEM object: ${obj?.javaClass}")
        }
    }

    /** Load full certificate chain (leaf + intermediates) from a PEM file. */
    private fun loadCertificateChain(certFile: File): Array<X509Certificate> {
        val certs = mutableListOf<X509Certificate>()
        val conv = JcaX509CertificateConverter()
        PEMParser(certFile.reader()).use { parser ->
            var obj = parser.readObject()
            while (obj != null) {
                if (obj is org.bouncycastle.cert.X509CertificateHolder) {
                    certs.add(conv.getCertificate(obj))
                }
                obj = parser.readObject()
            }
        }
        require(certs.isNotEmpty()) { "No certificates found in ${certFile.name}" }
        return certs.toTypedArray()
    }

    private fun buildSelfSignedContext(): SSLContext {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val dn = X500Principal("CN=localhost,O=Forge,C=US")
        val now = Date()
        val expiry = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_YEAR, 365)
        }.time

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val certHolder = JcaX509v3CertificateBuilder(
            dn,
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            expiry,
            dn,
            kp.public,
        ).build(signer)

        val cert = JcaX509CertificateConverter().getCertificate(certHolder)
        return buildContext(kp.private, arrayOf(cert))
    }

    private fun buildContext(privateKey: PrivateKey, chain: Array<X509Certificate>): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("leyline", privateKey, charArrayOf(), chain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
}