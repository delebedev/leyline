package leyline.infra

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class TlsHelperTest :
    FunSpec({

        tags(UnitTag)

        test("self-signed JDK SSLContext creates SSLEngine") {
            val ctx = TlsHelper.buildJdkSslContext(null, null)
            val engine = ctx.createSSLEngine()
            engine.useClientMode = false
            (engine.supportedProtocols.isNotEmpty()) shouldBe true
        }
    })
