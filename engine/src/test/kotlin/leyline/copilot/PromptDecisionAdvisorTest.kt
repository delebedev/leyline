package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.UnitTag

/** Pins typed advisor failure visibility without requiring a live Forge game. */
@Suppress("MissingAssertSoftly")
class PromptDecisionAdvisorTest :
    FunSpec({
        tags(UnitTag)

        test("unknown prompt is unavailable with an explicit unsupported reason") {
            val advisor = PromptDecisionAdvisor(ForgeAiPolicy({ error("unused") }, leyline.bridge.types.SeatId(1)))

            val result =
                advisor.decide(
                    wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
                        .getDefaultInstance(),
                )

            val unavailable = result.shouldBeInstanceOf<PromptDecisionResult.Unavailable>()
            unavailable.reason shouldBe PromptUnavailableReason.UnsupportedPrompt
            unavailable.detail shouldBe "no advisor route for None_aa0d"
        }
    })
