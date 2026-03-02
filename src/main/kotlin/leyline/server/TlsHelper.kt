package leyline.server

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
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
 * - PEM cert+key → [SSLContext] for JDK HttpsServer
 * - Self-signed cert generation for dev/test
 * - PKCS#1-aware key file normalization for Netty
 */
object TlsHelper {

    private val converter = JcaPEMKeyConverter()

    /**
     * Build a JDK [SSLContext] from PEM cert+key files, or self-signed if null.
     * Accepts both PKCS#1 and PKCS#8 key files.
     */
    fun buildJdkSslContext(certFile: File?, keyFile: File?): SSLContext {
        if (certFile != null && keyFile != null && certFile.exists() && keyFile.exists()) {
            val cert = loadCertificate(certFile)
            val key = loadPrivateKey(keyFile.readText())
            return buildContext(key, cert)
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

    private fun loadCertificate(certFile: File): X509Certificate {
        val obj = PEMParser(certFile.reader()).use { it.readObject() }
        return when (obj) {
            is org.bouncycastle.cert.X509CertificateHolder ->
                JcaX509CertificateConverter().getCertificate(obj)
            else -> {
                // Fall back to JDK CertificateFactory for DER or standard PEM
                certFile.inputStream().use {
                    java.security.cert.CertificateFactory.getInstance("X.509")
                        .generateCertificate(it) as X509Certificate
                }
            }
        }
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
        return buildContext(kp.private, cert)
    }

    private fun buildContext(privateKey: PrivateKey, cert: X509Certificate): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("leyline", privateKey, charArrayOf(), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
}
