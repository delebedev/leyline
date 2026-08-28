package leyline.config

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class NativeSettingsTest :
    FunSpec({
        tags(UnitTag)

        test("advertised Front Door authority and Match Door host share one external host") {
            assertSoftly {
                NativeSettings(fdPort = 31010, externalHost = "example.test").advertisedFdUri shouldBe "example.test:31010"
                NativeSettings(externalHost = "example.test:9999").advertisedFdUri shouldBe "example.test:9999"
                NativeSettings(externalHost = "example.test:9999").matchDoorHost shouldBe "example.test"
            }
        }

        test("external host rejects invalid authorities") {
            shouldThrow<IllegalArgumentException> {
                NativeSettings(externalHost = "example.test/path").validate()
            }
            shouldThrow<IllegalArgumentException> {
                NativeSettings(externalHost = "example.test:99999").validate()
            }
        }
    })
