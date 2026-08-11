package leyline.config

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.io.File

class MatchConfigTest :
    FunSpec({
        tags(UnitTag)

        test("bridge timeout defaults to disabled") {
            MatchConfig().server.bridgeTimeoutMs.shouldBeNull()
            shouldNotThrowAny { MatchConfig().validate() }
        }

        test("prompt failsafe defaults to finite and can be disabled") {
            MatchConfig().server.promptFailsafeMs shouldBe 45_000L
            ServerConfig(promptFailsafeMs = null).promptFailsafeMs.shouldBeNull()
        }

        test("mulligan wait defaults long enough for local play") {
            MatchConfig().server.mulliganWaitMs shouldBe 45_000L
        }

        test("automatic response delivery defaults to disabled") {
            MatchConfig().dev.copilotAutopush shouldBe false
        }

        test("bridge timeout must be positive when configured") {
            shouldThrow<IllegalArgumentException> {
                MatchConfig(server = ServerConfig(bridgeTimeoutMs = 0)).validate()
            }
        }
        test("draft picker can be configured from toml") {
            val file = File.createTempFile("leyline-config", ".toml")
            file.writeText(
                """
                [draft]
                picker = "model"
                model_dir = "custom/draft-models"
                """.trimIndent(),
            )

            val config = MatchConfig.load(file)

            config.draft.picker shouldBe "model"
            config.draft.modelDir shouldBe "custom/draft-models"
        }

        test("draft picker rejects unknown values") {
            shouldThrow<IllegalArgumentException> {
                MatchConfig(draft = DraftConfig(picker = "remote")).validate()
            }
        }

        test("prompt failsafe must be positive when configured") {
            shouldThrow<IllegalArgumentException> {
                MatchConfig(server = ServerConfig(promptFailsafeMs = 0)).validate()
            }
        }

        test("mulligan wait must be positive") {
            shouldThrow<IllegalArgumentException> {
                MatchConfig(server = ServerConfig(mulliganWaitMs = 0)).validate()
            }
        }
    })
