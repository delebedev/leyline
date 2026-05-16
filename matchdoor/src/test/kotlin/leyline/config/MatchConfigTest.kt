package leyline.config

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import leyline.UnitTag

class MatchConfigTest :
    FunSpec({
        tags(UnitTag)

        test("bridge timeout defaults to disabled") {
            MatchConfig().server.bridgeTimeoutMs.shouldBeNull()
            shouldNotThrowAny { MatchConfig().validate() }
        }

        test("bridge timeout must be positive when configured") {
            shouldThrow<IllegalArgumentException> {
                MatchConfig(server = ServerConfig(bridgeTimeoutMs = 0)).validate()
            }
        }
    })
