package leyline.session.combat

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

class EquipmentGrantedTriggerTest :
    SessionTest({
        for ((bearer, damage, birds) in listOf(
            Triple("Falcon and Redwing", 2, 2),
            Triple("Grizzly Bears", 3, 0),
        )) {
            session(
                "equipment on $bearer creates Treasures independently of native token triggers",
                forgeCatalog = true,
                puzzle = """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    removesummoningsickness=true
                    humanbattlefield=The Reaver Cleaver;$bearer;Plains;Plains;Plains
                    humanhand=Forest
                    humanlibrary=Plains;Plains;Plains;Plains;Plains
                    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """,
            ) {
                activateAbility("The Reaver Cleaver").shouldBeTrue()
                selectTargets(listOf(human.battlefield.iid(bearer)))
                passUntilResolved()
                human.battlefield.card("The Reaver Cleaver").attachedTo shouldBe human.battlefield.card(bearer)
                passUntil { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
                declareAttackers(listOf(human.battlefield.iid(bearer)))
                passUntil { ai.life == 20 - damage }.shouldBeTrue()
                passUntilResolved()
                passUntil { game().phaseHandler.phase.name == "MAIN2" }.shouldBeTrue()
                val tokens = human.getCardsIn(ZoneType.Battlefield).filter { it.isToken }
                val treasureGrpId = checkNotNull(bridge.cardRepository.findTokenGrpIdByScript("c_a_treasure_sac"))
                assertSoftly {
                    tokens.count { it.type.hasSubtype("Treasure") } shouldBe damage
                    tokens.count { it.type.hasSubtype("Bird") } shouldBe birds
                    tokens.filter { it.type.hasSubtype("Treasure") }.forEach {
                        bridge.resolveGrpId(it) shouldBe treasureGrpId
                    }
                    game().stack.isEmpty.shouldBeTrue()
                }
                playLand("Forest").shouldBeTrue()
            }
        }
    })
