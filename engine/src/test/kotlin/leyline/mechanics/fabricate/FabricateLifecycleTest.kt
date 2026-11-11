package leyline.mechanics.fabricate

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.battlefield
import leyline.tooling.headless.HeadlessResponseMode

class FabricateLifecycleTest :
    SessionTest({
        for ((label, card, other) in listOf(
            Triple("intrinsic", "Glint-Sleeve Artisan", ""),
            Triple("Cayth's own", "Cayth, Famed Mechanist", ""),
            Triple("Cayth-granted", "Ornithopter", ";Cayth, Famed Mechanist"),
        )) {
            for (accept in listOf(true, false)) {
                session(
                    "$label fabricate ${if (accept) "accepts counters" else "declines counters for a Servo"}",
                    puzzle =
                        """
                        ActivePlayer=Human
                        ActivePhase=Main1
                        HumanLife=20
                        AILife=20
                        humanhand=$card;Island
                        humanbattlefield=Island;Mountain;Plains;Plains;Plains$other
                        humanlibrary=Forest;Forest;Forest;Forest;Forest
                        ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                        """.trimIndent(),
                    responseMode = HeadlessResponseMode.PolicyVisible,
                ) {
                    val before = messageSnapshot()
                    castSpellByName(card) shouldBe true
                    passUntil(maxPasses = 8) { allMessages.any { it.hasOptionalActionMessage() } } shouldBe true
                    val source = human.battlefield.card(card)
                    val prompt = messagesSince(before).single { it.hasOptionalActionMessage() }
                    assertSoftly {
                        prompt.optionalActionMessage.sourceId shouldBe human.battlefield.iid(card)
                        prompt.prompt.parametersList
                            .single { it.parameterName == "CardId" }
                            .numberValue shouldBe
                            prompt.optionalActionMessage.sourceId
                        source.getCounters(CounterEnumType.P1P1) shouldBe 0
                        human.getCardsIn(ZoneType.Battlefield).count { it.isToken } shouldBe 0
                    }

                    respondToOptionalAction(accept)
                    passUntilResolved(maxPasses = 8)
                    val servos = human.getCardsIn(ZoneType.Battlefield).filter { it.isToken && it.name.startsWith("Servo") }
                    assertSoftly {
                        source.getCounters(CounterEnumType.P1P1) shouldBe if (accept) 1 else 0
                        servos.size shouldBe if (accept) 0 else 1
                        if (!accept) {
                            val servo = servos.single()
                            servo.netPower shouldBe 1
                            servo.netToughness shouldBe 1
                            servo.isArtifact shouldBe true
                            servo.isCreature shouldBe true
                            servo.isColorless shouldBe true
                        }
                        game().stackZone.size() shouldBe 0
                        game().isGameOver shouldBe false
                        messagesSince(before).count { it.hasOptionalActionMessage() } shouldBe 1
                        allMessages.last().hasActionsAvailableReq() shouldBe true
                    }
                }
            }
        }
    })
