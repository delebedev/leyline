package leyline.config

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class EngineSettingsTest :
    FunSpec({
        tags(UnitTag)

        test("defaults preserve the established engine behavior") {
            val defaults = EngineSettings()

            assertSoftly {
                defaults.bridgeTimeoutMs.shouldBeNull()
                defaults.promptFailsafeMs shouldBe 45_000L
                defaults.aiTurnWaitMs shouldBe 30_000L
                defaults.mulliganWaitMs shouldBe 45_000L
                defaults.seed.shouldBeNull()
                defaults.dieRollWinner.shouldBeNull()
                defaults.skipMulligan shouldBe false
                defaults.timer shouldBe true
                defaults.aiDeck.shouldBeNull()
                defaults.spectatorMode shouldBe false
                defaults.aiSpeed shouldBe 1.0
                defaults.draft.picker shouldBe "forge"
                defaults.draft.modelDir shouldBe "data/draft-models"
                defaults.dev.strict shouldBe false
                defaults.dev.strictPass shouldBe false
                defaults.dev.copilotAutopush shouldBe false
            }
        }

        test("defaults validate cleanly") {
            shouldNotThrowAny { EngineSettings().validate() }
        }

        test("pacing derivation mirrors the legacy aiDelayMultiplier contract") {
            assertSoftly {
                EngineSettings(aiSpeed = 0.0).aiDelayMultiplier shouldBe 0.0
                EngineSettings(aiSpeed = 1.0).aiDelayMultiplier shouldBe 1.0
                EngineSettings(aiSpeed = 2.0).aiDelayMultiplier shouldBe 0.5
                EngineSettings(aiSpeed = 2.0).paceDelayMs shouldBe 100L
                EngineSettings(aiSpeed = 0.0).paceDelayMs shouldBe 0L
            }
        }

        test("bridge timeout must be positive when configured") {
            shouldThrow<IllegalArgumentException> {
                EngineSettings(bridgeTimeoutMs = 0).validate()
            }
        }

        test("prompt failsafe must be positive when configured") {
            shouldThrow<IllegalArgumentException> {
                EngineSettings(promptFailsafeMs = 0).validate()
            }
        }

        test("mulligan wait and ai turn wait must be positive") {
            shouldThrow<IllegalArgumentException> { EngineSettings(mulliganWaitMs = 0).validate() }
            shouldThrow<IllegalArgumentException> { EngineSettings(aiTurnWaitMs = 0).validate() }
        }

        test("die roll winner must be seat 1 or 2") {
            shouldThrow<IllegalArgumentException> { EngineSettings(dieRollWinner = 3).validate() }
        }

        test("ai speed must be non-negative") {
            shouldThrow<IllegalArgumentException> { EngineSettings(aiSpeed = -1.0).validate() }
        }

        test("draft picker rejects unknown values") {
            shouldThrow<IllegalArgumentException> {
                EngineSettings(draft = DraftSettings(picker = "remote")).validate()
            }
        }
    })
