package leyline.mechanics.warp

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
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
        test("alternativeGrpId cast chooses the warp spell ability") {
            startPuzzleRaw(WARP_PUZZLE, validating = true)
            val warpAbilityGrpId = warpAbilityGrpId()

            check(castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId))
            check(passUntil(maxPasses = 20) { game().stack.isEmpty })

            assertSoftly {
                human.hasCard("Germinating Wurm", ZoneType.Hand) shouldBe false
                human.hasCardAnywhereExceptHand("Germinating Wurm") shouldBe true
            }
        }

        test("warp cast emits CastThroughAbility annotation for the selected rail") {
            startPuzzleRaw(WARP_PUZZLE, validating = true)
            val warpAbilityGrpId = warpAbilityGrpId()

            check(castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId))
            check(passUntil(maxPasses = 20) { game().stack.isEmpty })

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

        test("regular-cost cast keeps Germinating Wurm on the battlefield") {
            startPuzzleRaw(REGULAR_COST_PUZZLE, validating = true)

            check(castSpellByName("Germinating Wurm"))
            check(passUntil(maxPasses = 20) { game().stack.isEmpty })
            human.hasCard("Germinating Wurm", ZoneType.Battlefield) shouldBe true

            passUntilTurn(2, maxPasses = 30)

            assertSoftly {
                human.hasCard("Germinating Wurm", ZoneType.Battlefield) shouldBe true
                human.hasCard("Germinating Wurm", ZoneType.Exile) shouldBe false
                human.hasCard("Germinating Wurm", ZoneType.Graveyard) shouldBe false
                human.hasCard("Germinating Wurm", ZoneType.Hand) shouldBe false
            }
        }

        test("warp-cost cast exiles Germinating Wurm at end of turn") {
            startPuzzleRaw(WARP_END_STEP_PUZZLE, validating = true)
            val warpAbilityGrpId = warpAbilityGrpId()

            check(castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId))
            check(passUntil(maxPasses = 20) { game().stack.isEmpty })
            passUntilTurn(2, maxPasses = 30)

            assertSoftly {
                human.hasCard("Germinating Wurm", ZoneType.Exile) shouldBe true
                human.hasCard("Germinating Wurm", ZoneType.Battlefield) shouldBe false
                human.hasCard("Germinating Wurm", ZoneType.Graveyard) shouldBe false
                human.hasCard("Germinating Wurm", ZoneType.Hand) shouldBe false
            }
        }
    })

private fun SessionTest.warpAbilityGrpId(): Int {
    val repo = harness.bridge.cardRepository
    val wurmGrpId = repo.findGrpIdByName("Germinating Wurm")!!
    return repo.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!
}

private fun forge.game.player.Player.hasCard(
    name: String,
    zone: ZoneType,
): Boolean = getZone(zone).cards.any { it.name == name }

private fun forge.game.player.Player.hasCardAnywhereExceptHand(name: String): Boolean =
    hasCard(name, ZoneType.Battlefield) ||
        hasCard(name, ZoneType.Exile) ||
        hasCard(name, ZoneType.Graveyard)
