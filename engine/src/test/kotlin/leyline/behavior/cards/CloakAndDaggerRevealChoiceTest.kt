package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInZoneOf

/** Cloak and Dagger, Entwined offers a card from the revealed hand or the targeted creature. */
class CloakAndDaggerRevealChoiceTest :
    SessionTest({
        data class Case(
            val label: String,
            val creature: String,
            val faceDown: Boolean,
            val targetCreature: Boolean,
            val exileCreature: Boolean,
        )

        val cases =
            listOf(
                Case("without a targeted creature", "Super-Skrull", faceDown = false, targetCreature = false, exileCreature = false),
                Case(
                    "with a targeted creature, exiling the hand card",
                    "Super-Skrull",
                    faceDown = false,
                    targetCreature = true,
                    exileCreature = false,
                ),
                Case(
                    "with a targeted creature, exiling the creature",
                    "Super-Skrull",
                    faceDown = false,
                    targetCreature = true,
                    exileCreature = true,
                ),
                Case(
                    "with a targeted face-down creature, exiling it",
                    "Gathan Raiders",
                    faceDown = true,
                    targetCreature = true,
                    exileCreature = true,
                ),
            )

        for (case in cases) {
            val creatureEntry = if (case.faceDown) "${case.creature}|FaceDown" else case.creature
            session(
                "cloak and dagger resolves ${case.label}",
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
                    aibattlefield=$creatureEntry
                    ailibrary=Island;Island;Island
                    """.trimIndent(),
            ) {
                castSpellByName("Cloak and Dagger, Entwined").shouldBeTrue()
                passUntil(maxPasses = 10) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true }.shouldBeTrue()
                selectTargets(listOf(OPPONENT_SEAT))
                passUntil(maxPasses = 5) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true }.shouldBeTrue()
                val creatureCard = ai.getZone(ZoneType.Battlefield).cards.single()
                creatureCard.isFaceDown shouldBe case.faceDown
                val creature = ai.battlefield.iid(creatureCard)
                selectTargets(if (case.targetCreature) listOf(creature) else emptyList())
                passUntil(maxPasses = 10) { allMessages.lastOrNull()?.hasSelectNReq() == true }.shouldBeTrue()

                val req = allMessages.last().selectNReq
                val divination = ai.hand.iid("Divination")
                val expectedIds = if (case.targetCreature) setOf(divination, creature) else setOf(divination)
                assertSoftly {
                    req.idsList.toSet() shouldBe expectedIds
                    req.unfilteredIdsList.toSet() shouldBe expectedIds + ai.hand.iid("Island")
                }
                if (case.faceDown) {
                    // The chooser may not look at the face-down creature, so every state it receives keeps the placeholder.
                    val creatureObjects =
                        allMessages
                            .filter { it.hasGameStateMessage() }
                            .flatMap { it.gameStateMessage.gameObjectsList }
                            .filter { it.instanceId == creature }
                    creatureObjects.shouldNotBeEmpty()
                    creatureObjects.forEach { it.grpId shouldBe 3 }
                }

                respondToSelectN(listOf(if (case.exileCreature) creature else divination))
                passUntilResolved()
                assertSoftly {
                    if (case.exileCreature) {
                        case.creature should beInZoneOf(ZoneType.Exile, ai)
                        "Divination" should beInZoneOf(ZoneType.Hand, ai)
                    } else {
                        "Divination" should beInZoneOf(ZoneType.Exile, ai)
                        case.creature should beInZoneOf(ZoneType.Battlefield, ai)
                    }
                    "Island" should beInZoneOf(ZoneType.Hand, ai)
                }
            }
        }
    })
