package leyline.session.turns

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.PriorityActionCandidates
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class SacrificeManaPriorityTest :
    SessionTest({
        for (control in listOf(false, true)) {
            session(
                "repeatable sacrifice mana preserves an instant response fullControl=$control",
                fullControl = control,
                puzzle = sacrificeManaPuzzle(2),
                aiScript = listOf(ScriptedAction.CastSpell("Blasphemous Act")),
                turns = 3,
            ) {
                if (control) passUntil(maxPasses = 10) { !game().stack.isEmpty } shouldBe true
                game().stack.isEmpty shouldBe false
                PriorityActionCandidates.query(game(), human).hasLegalNonManaAction(human, false) shouldBe true
                for (color in listOf(ManaColor.Black_afc9, ManaColor.Green_afc9)) {
                    val offered =
                        accumulator.actions!!.actionsList.single {
                            it.actionType == ActionType.ActivateMana && it.instanceId == human.battlefield.iid("Utopia Mycon")
                        }
                    assertSoftly {
                        offered.manaSelectionsList.flatMap { it.optionsList }.any { it.selectedColor == color } shouldBe true
                        offered.manaPaymentOptionsList.any { option -> option.manaList.any { it.color == color } } shouldBe true
                    }
                    activateMana("Utopia Mycon", selectedColor = color) shouldBe true
                    respondToEffectCost(listOf(human.battlefield.iid("Saproling")))
                }
                val cto = castSpellUntilCastingTimeOptionsReq("Golgari Charm")
                val mode =
                    cto.getCastingTimeOptionReq(0).modalReq.modalOptionsList.single {
                        bridge.cardRepository
                            .findAbilityLocalization(it.grpId)
                            ?.text
                            ?.contains("Regenerate") == true
                    }
                respondModalChoice(listOf(mode.grpId))
                passUntilResolved()
                assertSoftly {
                    human.battlefield.card("Grizzly Bears")
                    ai.graveyard.card("Centaur Courser")
                    human.isCardInPlay("Saproling") shouldBe false
                }
                val nextMainReached =
                    passUntil(maxPasses = 20) {
                        game().phaseHandler.playerTurn == human &&
                            game().phaseHandler.phase.isMain
                    }
                nextMainReached shouldBe true
                playLand("Plains") shouldBe true
            }
        }

        for (tokens in listOf(0, 1)) {
            session(
                "insufficient sacrifice resources do not stop automatic priority tokens=$tokens",
                puzzle = sacrificeManaPuzzle(tokens),
                aiScript = listOf(ScriptedAction.CastSpell("Blasphemous Act")),
                turns = 3,
            ) {
                game().stack.isEmpty shouldBe true
                human.graveyard.card("Grizzly Bears")
                human.hand.card("Golgari Charm")
                playLand("Plains") shouldBe true
            }
        }

        for ((source, hand) in listOf("Ashnod's Altar" to "Golgari Charm", "Utopia Mycon" to "Ornithopter")) {
            session(
                "mana alone does not stop automatic priority source=$source hand=$hand",
                puzzle = sacrificeManaPuzzle(2, source, hand),
                aiScript = listOf(ScriptedAction.CastSpell("Blasphemous Act")),
                turns = 3,
            ) {
                game().stack.isEmpty shouldBe true
                human.graveyard.card("Grizzly Bears")
                human.hand.card(hand)
                playLand("Plains") shouldBe true
            }
        }
    })

private fun sacrificeManaPuzzle(
    tokens: Int,
    source: String = "Utopia Mycon",
    hand: String = "Golgari Charm",
): String =
    """
    ActivePlayer=AI
    ActivePhase=Main1
    HumanLife=40
    AILife=40
    humanhand=$hand
    humanbattlefield=${(
        listOf(source, "Grizzly Bears") +
            List(tokens) {
                "t:Saproling,P:1,T:1,Types:Creature-Saproling,Color:Green,Keywords:,Image:g_1_1_saproling"
            }
    ).joinToString(";")}
    humanlibrary=Plains;Plains;Plains;Plains
    aihand=Blasphemous Act
    aibattlefield=${(List(9) { "Mountain" } + "Centaur Courser").joinToString(";")}
    ailibrary=Island;Island;Island;Island
    """.trimIndent()
