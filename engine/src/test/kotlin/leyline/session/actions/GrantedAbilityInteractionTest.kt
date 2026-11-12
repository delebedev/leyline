package leyline.session.actions

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.allActions
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class GrantedAbilityInteractionTest :
    SessionTest({
        for (topCard in listOf("Forest", "Serra Angel")) {
            session(
                "a selected public grant on manifested $topCard executes its exact offered action",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Thousand-Year Elixir;Plains;Plains;Forest;Forest;Forest;Forest
                    humanhand=Soul Summons;Presence of Gond;Ornithopter
                    humanlibrary=$topCard;Island;Island;Island
                    ailibrary=Island;Island;Island;Island
                    """.trimIndent(),
            ) {
                castSpellByName("Soul Summons") shouldBe true
                passUntil(maxPasses = 10) { human.getZone(ZoneType.Battlefield).cards.any { it.isFaceDown } } shouldBe true
                val recipient = human.getZone(ZoneType.Battlefield).cards.single { it.isFaceDown }
                val iid = bridge.instanceId(recipient)
                castSpellByName("Presence of Gond") shouldBe true
                passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } } shouldBe true
                selectTargets(listOf(iid))
                passUntil(maxPasses = 10) { human.isCardInPlay("Presence of Gond") } shouldBe true
                val offered = allMessages.allActions().last { it.actionType == ActionType.Activate_add3 && it.instanceId == iid }
                val row =
                    accumulator.objects
                        .getValue(iid)
                        .uniqueAbilitiesList
                        .single { it.grpId == offered.abilityGrpId }
                assertSoftly {
                    row.id shouldBe offered.uniqueAbilityId
                    recipient.isFaceDown shouldBe true
                    human.getZone(ZoneType.Battlefield).cards.count { it.isToken } shouldBe 0
                }
                send(submitWithGsId(performAction(offered)))
                drainSink()
                assertSoftly {
                    passUntil(maxPasses = 10) { human.getZone(ZoneType.Battlefield).cards.any { it.isToken } } shouldBe true
                    recipient.isTapped shouldBe true
                    human.getZone(ZoneType.Battlefield).cards.count { it.isToken } shouldBe 1
                    recipient.isFaceDown shouldBe true
                }
                assertSoftly {
                    castSpellByName("Ornithopter") shouldBe true
                    passUntil(maxPasses = 10) { human.isCardInPlay("Ornithopter") } shouldBe true
                }
            }
        }
    })
