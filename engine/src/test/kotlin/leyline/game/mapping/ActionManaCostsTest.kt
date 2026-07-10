package leyline.game.mapping

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.StrictPromptRefusalException

class ActionManaCostsTest :
    FunSpec({

        tags(UnitTag)

        test("strict prompt refusal bypasses the defensive affordability fallback") {
            var fallbackCalled = false

            shouldThrow<StrictPromptRefusalException> {
                ActionManaCosts.affordabilityProbe(
                    probe = { throw StrictPromptRefusalException("unexpected prompt") },
                    fallback = {
                        fallbackCalled = true
                        false
                    },
                )
            }

            fallbackCalled shouldBe false
        }

        test("ordinary Forge failure uses the defensive affordability fallback") {
            ActionManaCosts.affordabilityProbe(
                probe = { error("unsupported cost") },
                fallback = { true },
            ) shouldBe true
        }
    })
