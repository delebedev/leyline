package leyline.session.costs

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.ParameterType

class OptionalManaTaxTest :
    SessionTest({
        fun puzzle(forests: Int = 2): String =
            """
            [metadata]
            Name:Optional draw tax
            Goal:Survive
            Turns:6
            Difficulty:Tutorial
            Description:Choose whether to pay a draw-triggered mana tax.
            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=${List(forests) { "Forest" }.joinToString(";")}
            humanhand=Grizzly Bears
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            aibattlefield=Smothering Tithe
            ailibrary=Forest;Forest;Forest;Forest;Forest
            """.trimIndent()

        for (case in listOf(
            TaxCase("decline retains mana and creates a Treasure", false, 2, 1, 0),
            TaxCase("accept pays the opponent tax with the drawing player's mana", true, 2, 0, 2),
            TaxCase("unpayable acceptance cannot suppress the Treasure", true, 1, 1, 0),
        )) {
            session(case.label, puzzle = puzzle(case.forests), fullControl = true) {
                holdNextOptionalAction()
                passUntil(maxPasses = 40) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
                allMessages.any { it.hasOptionalActionMessage() }.shouldBeTrue()
                val offer = allMessages.last { it.hasOptionalActionMessage() }
                val cost = offer.prompt.parametersList.single { it.parameterName == "Cost" }
                assertSoftly {
                    offer.optionalActionMessage.sourceId shouldBe ai.battlefield.iid("Smothering Tithe")
                    cost.type shouldBe ParameterType.NonLocalizedString
                    cost.stringValue shouldBe "{2}"
                    human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
                }
                respondToOptionalAction(accept = case.accept)
                advanceToMain1()
                assertSoftly {
                    human.getZone(ZoneType.Battlefield).cards.count { it.name == "Forest" && it.isTapped } shouldBe case.tapped
                    ai.getZone(ZoneType.Battlefield).cards.count { it.type.hasSubtype("Treasure") } shouldBe case.treasures
                }
                if (!case.accept) {
                    castSpellByName("Grizzly Bears").shouldBeTrue()
                    passUntilResolved()
                    human.battlefield
                        .card("Grizzly Bears")
                        .isCreature
                        .shouldBeTrue()
                }
            }
        }
        session("calculated mana payment retains Forge X evaluation", puzzle = puzzle(), fullControl = true) {
            val tax =
                ai.battlefield
                    .card("Smothering Tithe")
                    .triggers
                    .single()
                    .ensureAbility()
            tax.putParam("UnlessCost", "X")
            tax.setSVar("X", "Number$1")
            holdNextOptionalAction()
            passUntil(maxPasses = 40) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            allMessages.any { it.hasOptionalActionMessage() }.shouldBeTrue()
            val offer = allMessages.last { it.hasOptionalActionMessage() }
            offer.prompt.parametersList
                .single { it.parameterName == "Cost" }
                .stringValue shouldBe "{X}"
            respondToOptionalAction(accept = true)
            advanceToMain1()
            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 1
                ai.getZone(ZoneType.Battlefield).cards.count { it.type.hasSubtype("Treasure") } shouldBe 0
            }
        }

        session("zero mana payment retains the existing confirmation path", puzzle = puzzle(), fullControl = true) {
            val tax =
                ai.battlefield
                    .card("Smothering Tithe")
                    .triggers
                    .single()
                    .ensureAbility()
            tax.putParam("UnlessCost", "0")
            val startTurn = game().phaseHandler.turn
            passUntil(maxPasses = 40) {
                game().phaseHandler.turn > startTurn && phase() == "MAIN1" && !isAiTurn()
            }.shouldBeTrue()
            assertSoftly {
                allMessages.count { it.hasOptionalActionMessage() } shouldBe 0
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
                ai.getZone(ZoneType.Battlefield).cards.count { it.type.hasSubtype("Treasure") } shouldBe 0
            }
        }
    })

private data class TaxCase(
    val label: String,
    val accept: Boolean,
    val forests: Int,
    val treasures: Int,
    val tapped: Int,
)
