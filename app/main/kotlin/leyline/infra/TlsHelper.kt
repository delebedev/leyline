package leyline.infra

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.io.StringReader
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * TLS helpers — PEM cert loading, self-signed generation, and auto-generation
 * of mitmproxy-CA-signed certs for Arena client compatibility.
 *
 * Used by AccountServer (JDK HttpsServer needs [SSLContext]).
 * FD/MD use Netty's [SslContextBuilder] which reads PEM directly.
 */
object TlsHelper {

    private val log = LoggerFactory.getLogger(TlsHelper::class.java)
    private val converter = JcaPEMKeyConverter()

    private val MITMPROXY_CA_CERT = File(System.getProperty("user.home"), ".mitmproxy/mitmproxy-ca-cert.pem")
    private val MITMPROXY_CA_KEY = File(System.getProperty("user.home"), ".mitmproxy/mitmproxy-ca.pem")

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

    // -- Auto-cert generation ------------------------------------------------

    /**
     * Ensure valid mitmproxy-signed certs exist for current hostnames.
     * Returns (certFile, keyFile) or null if mitmproxy CA unavailable or
     * no FD/MD hostnames found in /etc/hosts.
     *
     * Regenerates when: certs missing, or SAN hostnames don't match /etc/hosts.
     */
    fun ensureCerts(certsDir: File): Pair<File, File>? {
        val hostnames = discoverHostnames()
        if (hostnames == null) {
            log.info("No FD/MD hostnames in /etc/hosts — skipping auto-cert")
            return null
        }
        val (fdHost, mdHost) = hostnames

        val certFile = File(certsDir, "frontdoor-combined.pem")
        val keyFile = File(certsDir, "frontdoor.key")

        if (certFile.exists() && keyFile.exists()) {
            val currentSans = extractSanDnsNames(certFile)
            if (currentSans.contains(fdHost) && currentSans.contains(mdHost)) {
                log.info("TLS certs up-to-date for {} / {}", fdHost, mdHost)
                return certFile to keyFile
            }
            log.info("TLS cert SANs stale (have={}, need={}/{}), regenerating", currentSans, fdHost, mdHost)
        } else {
            log.info("TLS certs missing, generating for {} / {}", fdHost, mdHost)
        }

        return generateMitmproxyCert(fdHost, mdHost, certsDir)
    }

    /**
     * Discover FD/MD hostnames from /etc/hosts (set by arena-update).
     * Returns (fdHostname, mdHostname) or null if not found.
     */
    fun discoverHostnames(): Pair<String, String>? {
        val hostsFile = File("/etc/hosts")
        if (!hostsFile.exists()) return null
        var fd: String? = null
        var md: String? = null
        for (line in hostsFile.readLines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || !trimmed.startsWith("127.0.0.1")) continue
            val host = trimmed.split("\\s+".toRegex()).getOrNull(1) ?: continue
            if (host.startsWith("frontdoor-mtga-production-")) fd = host
            if (host.startsWith("matchdoor-mtga-production-")) md = host
        }
        return if (fd != null && md != null) fd to md else null
    }

    /**
     * Generate a cert signed by the mitmproxy CA with SANs for FD + MD hostnames.
     * Writes frontdoor.crt, frontdoor.key, frontdoor-combined.pem to [outDir].
     * Returns (combined.pem, key) or null if mitmproxy CA is not installed.
     */
    private fun generateMitmproxyCert(
        fdHost: String,
        mdHost: String,
        outDir: File,
    ): Pair<File, File>? {
        if (!MITMPROXY_CA_CERT.exists() || !MITMPROXY_CA_KEY.exists()) {
            log.warn("mitmproxy CA not found at {} — cannot auto-generate certs", MITMPROXY_CA_CERT.parent)
            return null
        }

        val caCert = loadCertificateChain(MITMPROXY_CA_CERT).first()
        // mitmproxy-ca.pem is a combined file (cert + key) — extract key only
        val caKey = loadPrivateKeyFromCombinedPem(MITMPROXY_CA_KEY.readText())

        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = Date()
        val expiry = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_YEAR, 365)
        }.time

        val issuer = X500Name.getInstance(caCert.subjectX500Principal.encoded)
        val serial = BigInteger(64, SecureRandom())

        val sans = GeneralNames(
            arrayOf(
                GeneralName(GeneralName.dNSName, fdHost),
                GeneralName(GeneralName.dNSName, mdHost),
                GeneralName(GeneralName.dNSName, "localhost"),
                GeneralName(GeneralName.iPAddress, "127.0.0.1"),
            ),
        )

        val subject = X500Name("CN=$fdHost")
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            now,
            expiry,
            subject,
            kp.public,
        )
        certBuilder.addExtension(Extension.subjectAlternativeName, false, sans)

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(caKey)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        outDir.mkdirs()
        val certFile = File(outDir, "frontdoor.crt")
        val keyFile = File(outDir, "frontdoor.key")
        val combinedFile = File(outDir, "frontdoor-combined.pem")

        writePem(certFile, cert)
        // Write key as PKCS#8 (BEGIN PRIVATE KEY) — AccountServer's PEM parser expects this format.
        // JcaPEMWriter writes PKCS#1 (BEGIN RSA PRIVATE KEY) for java.security.PrivateKey objects,
        // so we write PKCS#8 manually using the key's encoded form (which is already PKCS#8 DER).
        writePkcs8Key(keyFile, kp.private)
        // Combined: server cert + CA cert chain (Netty reads cert chain from this file)
        combinedFile.writeText(certFile.readText() + MITMPROXY_CA_CERT.readText())

        log.info("Generated TLS certs in {} (SANs: {}, {}, localhost, 127.0.0.1)", outDir, fdHost, mdHost)
        return combinedFile to keyFile
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

    /**
     * Extract the private key from a PEM file that may contain multiple objects
     * (e.g. mitmproxy-ca.pem has both cert and key). Loops through all objects
     * until a key is found.
     */
    private fun loadPrivateKeyFromCombinedPem(pem: String): PrivateKey {
        PEMParser(StringReader(pem)).use { parser ->
            var obj = parser.readObject()
            while (obj != null) {
                when (obj) {
                    is PEMKeyPair -> return converter.getKeyPair(obj).private
                    is PrivateKeyInfo -> return converter.getPrivateKey(obj)
                }
                obj = parser.readObject()
            }
        }
        error("No private key found in combined PEM")
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

    /** Extract DNS names from the SAN extension of the first cert in a PEM file. */
    private fun extractSanDnsNames(certFile: File): Set<String> {
        return try {
            val certs = loadCertificateChain(certFile)
            val sans = certs.first().subjectAlternativeNames ?: return emptySet()
            sans.filter { it[0] == 2 }.mapNotNull { it[1] as? String }.toSet()
        } catch (_: Exception) {
            emptySet() // corrupt cert → triggers regeneration
        }
    }

    private fun writePem(file: File, obj: Any) {
        file.writer().use { w -> JcaPEMWriter(w).use { it.writeObject(obj) } }
    }

    /** Write a private key in PKCS#8 PEM format (BEGIN PRIVATE KEY). */
    private fun writePkcs8Key(file: File, key: PrivateKey) {
        val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(key.encoded)
        file.writeText("-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n")
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
