package leyline.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import leyline.UnitTag
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class TlsHelperTest :
    FunSpec({

        tags(UnitTag)

        test("round-trip: PKCS#1 PEM converts to valid PKCS#8 key") {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

            val pkcs1Bytes = extractPkcs1FromPkcs8(kp.private.encoded)
            val pkcs1Pem = "-----BEGIN RSA PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs1Bytes) +
                "\n-----END RSA PRIVATE KEY-----\n"

            val converted = TlsHelper.convertPkcs1ToPkcs8(pkcs1Pem)

            converted shouldContain "BEGIN PRIVATE KEY"
            converted shouldNotContain "BEGIN RSA PRIVATE KEY"

            // Parse back and verify key equality
            val pemBody = converted
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val recovered = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pemBody)))
            recovered.encoded shouldBe kp.private.encoded
        }

        test("PKCS#8 PEM passes through unchanged") {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val pem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"

            TlsHelper.convertPkcs1ToPkcs8(pem) shouldBe pem
        }

        test("self-signed JDK SSLContext creates SSLEngine") {
            val ctx = TlsHelper.buildJdkSslContext(null, null)
            val engine = ctx.createSSLEngine()
            engine.useClientMode = false
            (engine.supportedProtocols.isNotEmpty()) shouldBe true
        }
    })

/** Extract raw RSA key (PKCS#1) from PKCS#8 by stripping the ASN.1 outer wrapper. */
private fun extractPkcs1FromPkcs8(pkcs8: ByteArray): ByteArray {
    var i = 0
    fun readByte() = pkcs8[i++].toInt() and 0xFF
    fun readLength(): Int {
        val first = readByte()
        if (first < 0x80) return first
        val n = first and 0x7F
        var len = 0
        repeat(n) { len = (len shl 8) or readByte() }
        return len
    }

    // Outer SEQUENCE
    check(readByte() == 0x30)
    readLength()
    // Version INTEGER (may be absent in some encodings)
    if (readByte() == 0x02) {
        val vl = readLength()
        i += vl
    } else {
        i--
    }
    // AlgorithmIdentifier SEQUENCE — skip
    check(readByte() == 0x30)
    val algLen = readLength()
    i += algLen
    // OCTET STRING containing PKCS#1
    check(readByte() == 0x04)
    val octetLen = readLength()
    return pkcs8.copyOfRange(i, i + octetLen)
}
