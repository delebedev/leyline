package leyline.game.mapper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag

/**
 * Lock down PromptId constants against real server values.
 *
 * Evidence: conformance observatory — `just segment-variance` + `just conform-proto`
 * against 10+ recording sessions. See issue #194.
 */
class PromptIdsTest :
    FunSpec({
        tags(ConformanceTag)

        test("verified constants match real server values") {
            PromptIds.PASS_PRIORITY shouldBe 2
            PromptIds.DECLARE_ATTACKERS shouldBe 6
            PromptIds.ORDER_BLOCKERS shouldBe 7
            PromptIds.ASSIGN_DAMAGE shouldBe 8
            PromptIds.SELECT_TARGETS shouldBe 10
            PromptIds.PAY_COSTS shouldBe 11
            PromptIds.CASTING_TIME_OPTIONS shouldBe 23
            PromptIds.MATCH_RESULT_WIN_LOSS shouldBe 27
            PromptIds.REVEAL_HAND shouldBe 29
            PromptIds.DRAW_CARD shouldBe 30
            PromptIds.MULLIGAN shouldBe 34
            PromptIds.STARTING_PLAYER shouldBe 37
            PromptIds.GROUP_SCRY shouldBe 92
            PromptIds.GROUP_SURVEIL shouldBe 129
            PromptIds.SEARCH shouldBe 1065
        }

        test("DECLARE_ATTACKERS is distinct from SELECT_TARGETS") {
            // Previously both used the same constant name — ensure they're separate
            (PromptIds.DECLARE_ATTACKERS != PromptIds.SELECT_TARGETS) shouldBe true
        }
    })
