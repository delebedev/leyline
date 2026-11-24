package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import leyline.testkit.SessionTest

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
    })
