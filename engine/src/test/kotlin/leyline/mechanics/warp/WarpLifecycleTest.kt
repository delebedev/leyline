package leyline.mechanics.warp

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.hasCard
import leyline.tooling.headless.HeadlessMatch
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

private val WARP_PUZZLE =
    """
    [metadata]
    Name:Warp - Germinating Wurm cast for warp cost
    Goal:Cast Germinating Wurm from hand for its warp cost ({1}{G}).
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Germinating Wurm
    humanbattlefield=Forest;Forest
    humanlibrary=Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

private val REGULAR_COST_PUZZLE =
    """
    [metadata]
    Name:Warp - regular-cost cast stays on battlefield
    Goal:Cast Germinating Wurm for 4G; it stays post-turn.
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Germinating Wurm
    humanbattlefield=Forest;Forest;Forest;Forest;Forest
    humanlibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

private val WARP_END_STEP_PUZZLE =
    """
    [metadata]
    Name:Warp - warp-cost cast exiles at end of turn
    Goal:Cast Germinating Wurm for {1}{G}; exiled at end of turn.
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Germinating Wurm
    humanbattlefield=Forest;Forest;Forest;Forest;Forest
    humanlibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

@Suppress("UnnecessaryNotNullOperator")
class WarpLifecycleTest :
    SessionTest({
        session("alternativeGrpId cast chooses the warp spell ability", puzzle = WARP_PUZZLE) {
            val warpAbilityGrpId = warpAbilityGrpId()

            check(castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId))
            check(passUntil(maxPasses = 20) { observe().stackSize == 0 })

            assertSoftly {
                human.hand.hasCard("Germinating Wurm") shouldBe false
                human.hasCardAnywhereExceptHand("Germinating Wurm") shouldBe true
            }
        }

        session("warp cast emits CastThroughAbility annotation for the selected rail", puzzle = WARP_PUZZLE) {
            val warpAbilityGrpId = warpAbilityGrpId()

            check(castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId))
            check(passUntil(maxPasses = 20) { observe().stackSize == 0 })

            val cto =
                allMessages
                    .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
                    .flatMap { it.persistentAnnotationsList }
                    .firstOrNull { it.typeList.contains(AnnotationType.CastingTimeOption) }

            assertSoftly(cto) {
                it shouldNotBe null
                it!!.detailInt("type") shouldBe 13
                it.detailInt("alternateCostGrpId") shouldBe warpAbilityGrpId
                it.detailInt("castAbilityGrpId") shouldBe warpAbilityGrpId
            }
        }

        session("regular-cost cast keeps Germinating Wurm on the battlefield", puzzle = REGULAR_COST_PUZZLE) {
            check(castSpellByName("Germinating Wurm"))
            check(passUntil(maxPasses = 20) { observe().stackSize == 0 })
            human.battlefield.hasCard("Germinating Wurm") shouldBe true

            passUntilTurn(2, maxPasses = 30)

            // One copy in the puzzle, so battlefield membership excludes the rest.
            human.battlefield.hasCard("Germinating Wurm") shouldBe true
        }

        session("warp-cost cast exiles Germinating Wurm at end of turn", puzzle = WARP_END_STEP_PUZZLE) {
            val warpAbilityGrpId = warpAbilityGrpId()

            check(castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId))
            check(passUntil(maxPasses = 20) { observe().stackSize == 0 })
            passUntilTurn(2, maxPasses = 30)

            assertSoftly {
                human.exile.hasCard("Germinating Wurm") shouldBe true
            }
        }
    })

private fun HeadlessMatch.warpAbilityGrpId(): Int = keywordAbilityGrpId("Germinating Wurm", KeywordAbilityIds.WARP)!!
