package leyline.infra

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import leyline.UnitTag
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringWriter
import java.security.KeyPairGenerator

class TlsHelperTest :
    FunSpec({

        tags(UnitTag)

        test("round-trip: PKCS#1 PEM converts to valid PKCS#8") {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

            // JcaPEMWriter writes KeyPair as PKCS#1 (BEGIN RSA PRIVATE KEY)
            val pkcs1Pem = StringWriter().also { sw ->
                JcaPEMWriter(sw).use { it.writeObject(kp) }
            }.toString()
            pkcs1Pem shouldContain "BEGIN RSA PRIVATE KEY"

            val converted = TlsHelper.convertPkcs1ToPkcs8(pkcs1Pem)

            converted shouldContain "BEGIN PRIVATE KEY"
            converted shouldNotContain "BEGIN RSA PRIVATE KEY"
        }

        test("PKCS#8 PEM passes through unchanged") {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

            // Write as PKCS#8 via PemWriter (JCA .encoded is always PKCS#8)
            val pkcs8Pem = StringWriter().also { sw ->
                PemWriter(sw).use { it.writeObject(PemObject("PRIVATE KEY", kp.private.encoded)) }
            }.toString()
            pkcs8Pem shouldContain "BEGIN PRIVATE KEY"

            TlsHelper.convertPkcs1ToPkcs8(pkcs8Pem) shouldBe pkcs8Pem
        }

        test("self-signed JDK SSLContext creates SSLEngine") {
            val ctx = TlsHelper.buildJdkSslContext(null, null)
            val engine = ctx.createSSLEngine()
            engine.useClientMode = false
            (engine.supportedProtocols.isNotEmpty()) shouldBe true
        }
    })
