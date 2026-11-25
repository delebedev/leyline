package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GroupType
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import forge.game.zone.ZoneType as ForgeZoneType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as GreZoneType

/**
 * Fact or Fiction cast by the human: the AI separates the revealed cards and the
 * caster answers one two-option pile choice. The chosen pile goes to hand and the
 * other to the graveyard; the choice is not asked again.
 */
class FactOrFictionTest :
    SessionTest({

        session(
            "Fact or Fiction: caster chooses one of the separated piles once",
            puzzleFile = "data/puzzles/fact-or-fiction.pzl",
        ) {
            val revealed = setOf("Lightning Bolt", "Counterspell", "Negate", "Brainstorm", "Ponder")
            val req = castSpellUntilSelectNReq("Fact or Fiction")
            val labels =
                allMessages
                    .last { it.hasSelectNReq() }
                    .selectNReq.prompt.parametersList
                    .map { it.stringValue }

            assertSoftly {
                req.idsList shouldHaveSize 2
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                labels shouldHaveSize 2
                labels[0] shouldStartWith "Pile 1:"
                labels[1] shouldStartWith "Pile 2:"
            }

            fun pileCards(label: String): List<String> =
                label
                    .substringAfter(": ")
                    .split(", ")
                    .filter { it in revealed }
            val chosen = pileCards(labels[1])
            val unchosen = pileCards(labels[0])
            (chosen + unchosen) shouldContainExactlyInAnyOrder revealed

            val selectNBefore = allMessages.count { it.hasSelectNReq() }
            respondToSelectN(listOf(req.idsList[1]))
            passUntilResolved()

            assertSoftly {
                allMessages.count { it.hasSelectNReq() } shouldBe selectNBefore
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .map { it.name }
                    .filter { it in revealed } shouldContainExactlyInAnyOrder chosen
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContainExactlyInAnyOrder
                    unchosen + "Fact or Fiction"
            }
        }

        session(
            "Fact or Fiction: the player separates the AI caster's revealed cards into two piles",
            puzzleFile = "data/puzzles/fact-or-fiction-ai-caster.pzl",
        ) {
            val revealed = setOf("Lightning Bolt", "Counterspell", "Negate", "Brainstorm", "Ponder")
            passUntil { allMessages.any { it.hasGroupReq() } }
            allMessages.any { it.hasGroupReq() }.shouldBeTrue()
            val message = allMessages.last { it.hasGroupReq() }
            val req = message.groupReq
            val visible =
                allMessages
                    .takeWhile { it !== message }
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.grpId != 0 }
                    .map { it.instanceId }
                    .toSet()
            val names =
                req.instanceIdsList.associateWith { id ->
                    ai
                        .getZone(ForgeZoneType.Library)
                        .cards
                        .single {
                            bridge.instanceId(it) ==
                                id
                        }.name
                }

            assertSoftly {
                message.systemSeatIdsList shouldBe listOf(1)
                message.allowCancel shouldBe AllowCancel.No_a526
                req.groupType shouldBe GroupType.Arbitrary
                req.context shouldBe GroupingContext.None_a0f6
                req.groupSpecsList shouldHaveSize 2
                req.groupSpecsList.forEach {
                    it.lowerBound shouldBe 0
                    it.upperBound shouldBe Int.MAX_VALUE
                    it.zoneType shouldBe GreZoneType.None_a5ce
                }
                names.values shouldContainExactlyInAnyOrder revealed
                visible.containsAll(req.instanceIdsList) shouldBe true
            }

            val pile1 = req.instanceIdsList.take(2)
            val pile2 = req.instanceIdsList.drop(2)
            respondToGroupReq(awayInstanceIds = pile2, allInstanceIds = req.instanceIdsList)
            passUntil { game().stackZone.isEmpty }.shouldBeTrue()

            val aiHand =
                ai
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .map { it.name }
                    .filter { it in revealed }
            val aiGraveyard = ai.getZone(ForgeZoneType.Graveyard).cards.map { it.name }
            val piles = listOf(pile1.map(names::getValue), pile2.map(names::getValue))
            assertSoftly {
                piles.any { it.toSet() == aiHand.toSet() && it.size == aiHand.size } shouldBe true
                (aiHand + aiGraveyard.filter { it in revealed }) shouldContainExactlyInAnyOrder revealed
                aiGraveyard shouldContain "Fact or Fiction"
            }
        }
    })
