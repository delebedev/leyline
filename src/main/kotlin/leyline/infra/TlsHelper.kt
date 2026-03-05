package leyline.infra

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.StringReader
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
 * TLS helpers — PEM cert loading + self-signed generation.
 *
 * Used by MockWAS (JDK HttpsServer needs [SSLContext]).
 * FD/MD use Netty's [SslContextBuilder] which reads PEM directly.
 */
object TlsHelper {

    private val converter = JcaPEMKeyConverter()

    /**
     * Build a JDK [SSLContext] from PEM cert+key files, or self-signed if null.
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

    // -- Internals -----------------------------------------------------------

    private fun loadPrivateKey(pem: String): PrivateKey {
        val obj = PEMParser(StringReader(pem)).use { it.readObject() }
        return when (obj) {
            is PEMKeyPair -> converter.getKeyPair(obj).private
            is PrivateKeyInfo -> converter.getPrivateKey(obj)
            else -> error("Unexpected PEM object: ${obj?.javaClass}")
        }
    }

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
        val dn = X500Principal("CN=localhost,O=Leyline,C=US")
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
