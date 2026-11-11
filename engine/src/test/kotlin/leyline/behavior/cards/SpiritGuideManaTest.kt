package leyline.behavior.cards

import forge.card.MagicColor
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.ints.shouldNotBeZero
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInExileOf
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beOnBattlefieldOf
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class SpiritGuideManaTest :
    SessionTest({
        for (withCodie in listOf(false, true)) {
            for (red in listOf(false, true)) {
                val guide = if (red) "Simian Spirit Guide" else "Elvish Spirit Guide"
                val spell = if (red) "Lightning Bolt" else "Fog"
                session(
                    "$guide manual hand activation funds $spell and next land with Codie=$withCodie",
                    fullControl = true,
                    puzzle = """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=${if (withCodie) "Codie, Vociferous Codex" else ""}
                    humanhand=$guide;$spell;Forest;Elvish Mystic
                    humanlibrary=Forest;Forest;Forest
                    ailibrary=Forest;Forest;Forest
                """,
                ) {
                    val iid = human.hand.iid(guide)
                    if (withCodie) {
                        human.battlefield
                            .card("Codie, Vociferous Codex")
                            .staticAbilities.size shouldBe 1
                    }
                    val offer =
                        allMessages
                            .last { it.hasActionsAvailableReq() }
                            .actionsAvailableReq.actionsList
                            .single { it.actionType == ActionType.ActivateMana && it.instanceId == iid }
                    offer.abilityGrpId.shouldNotBeZero()
                    submitAction(offer)
                    val guideGrpId = bridge.cardRepository.findGrpIdByName(guide)!!
                    val afterActivation = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq
                    assertSoftly {
                        guide should beInExileOf(human)
                        human.manaPool.totalMana() shouldBe 1
                        human.manaPool.getAmountOfColor(if (red) MagicColor.RED else MagicColor.GREEN) shouldBe 1
                        game().stack.isEmpty shouldBe true
                        afterActivation.actionsList.none { it.actionType == ActionType.ActivateMana && it.grpId == guideGrpId } shouldBe
                            true
                    }
                    if (!red) {
                        if (withCodie) {
                            castSpellByName("Elvish Mystic") shouldBe true
                            assertSoftly {
                                human.hand.card("Elvish Mystic").name shouldBe "Elvish Mystic"
                                human.manaPool.totalMana() shouldBe 1
                                game().stack.isEmpty shouldBe true
                            }
                        } else {
                            val mystic = human.hand.iid("Elvish Mystic")
                            afterActivation.actionsList.any { it.actionType == ActionType.Cast && it.instanceId == mystic } shouldBe true
                        }
                    }
                    castSpellByName(spell) shouldBe true
                    if (red) selectTargets(listOf(OPPONENT_SEAT))
                    passUntilResolved()
                    assertSoftly {
                        spell should beInGraveyardOf(human)
                        human.manaPool.totalMana() shouldBe 0
                        ai.life shouldBe if (red) 17 else 20
                    }
                    assertSoftly {
                        playLand("Forest") shouldBe true
                        "Forest" should beOnBattlefieldOf(human)
                    }
                }
            }

            session(
                "Spirit Guide automatic green payment remains available with Codie=$withCodie",
                fullControl = true,
                puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=${if (withCodie) "Codie, Vociferous Codex" else ""}
                humanhand=Elvish Spirit Guide;Fog;Forest
                humanlibrary=Forest;Forest;Forest
                ailibrary=Forest;Forest;Forest
            """,
            ) {
                castSpellByName("Fog") shouldBe true
                passUntilResolved()
                assertSoftly {
                    "Elvish Spirit Guide" should beInExileOf(human)
                    "Fog" should beInGraveyardOf(human)
                    human.manaPool.totalMana() shouldBe 0
                }
                assertSoftly {
                    playLand("Forest") shouldBe true
                    "Forest" should beOnBattlefieldOf(human)
                }
            }
        }

        for (zone in listOf("battlefield", "graveyard", "exile")) {
            session(
                "Spirit Guide does not offer its hand ability from $zone",
                fullControl = true,
                puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                human$zone=Elvish Spirit Guide
                humanhand=Forest
                humanlibrary=Forest;Forest;Forest
                ailibrary=Forest;Forest;Forest
            """,
            ) {
                val grpId = bridge.cardRepository.findGrpIdByName("Elvish Spirit Guide")!!
                val forest = human.hand.iid("Forest")
                assertSoftly {
                    allMessages
                        .last { it.hasActionsAvailableReq() }
                        .actionsAvailableReq.actionsList
                        .none { it.actionType == ActionType.ActivateMana && (it.grpId == grpId || it.instanceId == forest) } shouldBe true
                    human.manaPool.totalMana() shouldBe 0
                }
                assertSoftly {
                    playLand("Forest") shouldBe true
                    "Forest" should beOnBattlefieldOf(human)
                }
            }
        }
    })
