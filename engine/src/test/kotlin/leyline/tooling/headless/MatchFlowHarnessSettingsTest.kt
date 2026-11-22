package leyline.tooling.headless

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class MatchFlowHarnessSettingsTest :
    FunSpec({
        tags(UnitTag)

        test("gameplay waits and the bridge outlast the headless AI limit") {
            val aiLimitMs = MatchFlowHarness.HEADLESS_AI_TIMEOUT_SECONDS * 1_000L
            val settings = MatchFlowHarness.headlessEngineSettings()
            assertSoftly {
                settings.aiTimeoutSeconds shouldBe MatchFlowHarness.HEADLESS_AI_TIMEOUT_SECONDS
                MatchFlowHarness.GAMEPLAY_WAIT_MS shouldBeGreaterThan aiLimitMs
                settings.bridgeTimeoutMs.shouldNotBeNull() shouldBeGreaterThan aiLimitMs
            }
        }
    })
