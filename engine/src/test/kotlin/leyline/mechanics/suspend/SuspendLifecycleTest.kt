package leyline.mechanics.suspend

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.BlockingInteraction
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class SuspendLifecycleTest :
    SessionTest({
        session(
            "suspend exiles the card with time counters and each of its controller's upkeeps removes one",
            puzzleFile = "data/puzzles/suspend-crashing-footfalls.pzl",
        ) {
            val footfalls = "Crashing Footfalls"
            val start = messageSnapshot()
            castSpellByName(footfalls, alternativeGrpId = suspendRow(footfalls)).shouldBeTrue()

            assertSoftly {
                timeCounters(footfalls) shouldBe 4
                annotationsSince(start).any { AnnotationType.CounterAdded in it.typeList } shouldBe true
                human.getZone(ZoneType.Battlefield).cards.count { it.name == "Forest" && it.isTapped } shouldBe 1
                game().stackZone.size() shouldBe 0
            }

            val turnsAtRemoval = mutableListOf<Int>()
            for (remaining in 3 downTo 1) {
                passUntil(maxPasses = 40) { timeCounters(footfalls) == remaining }.shouldBeTrue()
                turnsAtRemoval += turn()
            }
            assertSoftly {
                turnsAtRemoval shouldContainExactly listOf(3, 5, 7)
                passUntil(maxPasses = 40) { freeCastOffered() }.shouldBeTrue()
                turn() shouldBe 9
            }
            respondToOptionalAction(accept = true)
            passUntilResolved()
            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.count { it.isToken && it.name.contains("Rhino") } shouldBe 2
                human.graveyard.card(footfalls).name shouldBe footfalls
            }
        }

        session(
            "the last time counter casts Rift Bolt for free and it deals 3 to its target",
            puzzleFile = "data/puzzles/suspend-rift-bolt.pzl",
        ) {
            castSpellByName("Rift Bolt", alternativeGrpId = suspendRow("Rift Bolt")).shouldBeTrue()
            timeCounters("Rift Bolt") shouldBe 1

            val upkeep = messageSnapshot()
            passUntil(maxPasses = 40) { freeCastOffered() }.shouldBeTrue()
            assertSoftly {
                turn() shouldBe 3
                timeCounters("Rift Bolt") shouldBe 0
                annotationsSince(upkeep).any { AnnotationType.CounterRemoved in it.typeList } shouldBe true
            }

            respondToOptionalAction(accept = true)
            allMessages.last().hasSelectTargetsReq() shouldBe true

            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            assertSoftly {
                ai.life shouldBe 17
                human.graveyard.card("Rift Bolt").name shouldBe "Rift Bolt"
                human.getZone(ZoneType.Battlefield).cards.count { it.name == "Mountain" && it.isTapped } shouldBe 0
            }
        }
    })

private fun MatchFlowHarness.suspendRow(cardName: String): Int {
    val grpId = bridge.cardRepository.findGrpIdByName(cardName)!!
    return bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.SUSPEND)!!
}

private fun MatchFlowHarness.freeCastOffered(): Boolean =
    bridge.cutCoordinator.currentBlockingInteraction()?.interaction is BlockingInteraction.Optional &&
        allMessages.last().hasOptionalActionMessage()

private fun MatchFlowHarness.timeCounters(cardName: String): Int =
    human
        .getZone(ZoneType.Exile)
        .cards
        .singleOrNull { it.name == cardName }
        ?.getCounters(CounterEnumType.TIME) ?: 0
