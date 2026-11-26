package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInZoneOf

/** Cloak and Dagger, Entwined offers a card from the revealed hand or the targeted creature. */
class CloakAndDaggerRevealChoiceTest :
    SessionTest({
        for ((targetCreature, exileCreature) in listOf(false to false, true to false, true to true)) {
            val label = if (targetCreature) "with" else "without"
            val chosenName = if (exileCreature) "Super-Skrull" else "Divination"
            session(
                "cloak and dagger resolves $label a targeted creature and exiles $chosenName",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Cloak and Dagger, Entwined
                    humanbattlefield=Plains;Swamp;Swamp
                    humanlibrary=Plains;Plains;Plains
                    aihand=Divination;Island
                    aibattlefield=Super-Skrull
                    ailibrary=Island;Island;Island
                    """.trimIndent(),
            ) {
                castSpellByName("Cloak and Dagger, Entwined").shouldBeTrue()
                passUntil(maxPasses = 10) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true }.shouldBeTrue()
                selectTargets(listOf(OPPONENT_SEAT))
                passUntil(maxPasses = 5) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true }.shouldBeTrue()
                val creature = ai.battlefield.iid("Super-Skrull")
                selectTargets(if (targetCreature) listOf(creature) else emptyList())
                passUntil(maxPasses = 10) { allMessages.lastOrNull()?.hasSelectNReq() == true }.shouldBeTrue()

                val req = allMessages.last().selectNReq
                val divination = ai.hand.iid("Divination")
                val expectedIds = if (targetCreature) setOf(divination, creature) else setOf(divination)
                assertSoftly {
                    req.idsList.toSet() shouldBe expectedIds
                    req.unfilteredIdsList.toSet() shouldBe expectedIds + ai.hand.iid("Island")
                }

                respondToSelectN(listOf(if (exileCreature) creature else divination))
                passUntilResolved()
                assertSoftly {
                    chosenName should beInZoneOf(ZoneType.Exile, ai)
                    if (!exileCreature) "Super-Skrull" should beInZoneOf(ZoneType.Battlefield, ai)
                    if (exileCreature) "Divination" should beInZoneOf(ZoneType.Hand, ai)
                    "Island" should beInZoneOf(ZoneType.Hand, ai)
                }
            }
        }
    })
