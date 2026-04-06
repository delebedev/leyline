package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Optional cost interactions — kicker, buyback, multikicker (future).
 *
 * Tests the CastingTimeOptionsReq/Resp protocol: prompt shape, accept/decline,
 * and prompt ordering relative to targeting.
 *
 * Card: Burst Lightning ({R}, kicker {4} — deals 2 damage, or 4 if kicked).
 */
class OptionalCostInteractionTest :
    InteractionTest({

        val burstPuzzle = """
            [metadata]
            Name:Burst Lightning
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Burst Lightning
            humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
            humanlibrary=Mountain
            aibattlefield=Centaur Courser
            ailibrary=Mountain
        """.trimIndent()

        /** Find the CastingTimeOptionsReq from the latest messages. */
        fun lastCtoReq(): CastingTimeOptionsReq =
            harness.allMessages.last { it.hasCastingTimeOptionsReq() }.castingTimeOptionsReq

        /** Accept kicker — send the Kicker option's ctoId. */
        fun acceptKicker() {
            val kickerOption = lastCtoReq().castingTimeOptionReqList.first {
                it.castingTimeOptionType == CastingTimeOptionType.Kicker
            }
            harness.respondToOptionalCost(kickerOption.ctoId)
        }

        /** Decline kicker — send the Done option's ctoId (0). */
        fun declineKicker() {
            val doneOption = lastCtoReq().castingTimeOptionReqList.first {
                it.castingTimeOptionType == CastingTimeOptionType.Done
            }
            harness.respondToOptionalCost(doneOption.ctoId)
        }

        test("CastingTimeOptionsReq — kicker prompt shape") {
            startPuzzle(burstPuzzle)

            val snap = harness.messageSnapshot()
            castSpellByName("Burst Lightning").shouldBeTrue()
            val castMessages = harness.messagesSince(snap)

            val ctoReq = castMessages.first { it.hasCastingTimeOptionsReq() }
                .castingTimeOptionsReq

            // Two options: Kicker + Done
            ctoReq.castingTimeOptionReqList shouldHaveSize 2

            val kickerOption = ctoReq.castingTimeOptionReqList.first {
                it.castingTimeOptionType == CastingTimeOptionType.Kicker
            }
            val doneOption = ctoReq.castingTimeOptionReqList.first {
                it.castingTimeOptionType == CastingTimeOptionType.Done
            }

            assertSoftly {
                kickerOption.ctoId shouldBe 1
                doneOption.ctoId shouldBe 0
                doneOption.isRequired.shouldBeTrue()
            }
        }

        test("kicked Burst Lightning deals 4 damage") {
            startPuzzle(burstPuzzle)

            castSpellByName("Burst Lightning").shouldBeTrue()
            acceptKicker()
            selectTargets(listOf(2))
            passUntilResolved()

            ai.life shouldBe 16
        }

        test("unkicked Burst Lightning deals 2 damage") {
            startPuzzle(burstPuzzle)

            castSpellByName("Burst Lightning").shouldBeTrue()
            declineKicker()
            selectTargets(listOf(2))
            passUntilResolved()

            ai.life shouldBe 18
        }

        test("optional cost prompt gates targeting — no SelectTargetsReq before response") {
            startPuzzle(burstPuzzle)

            val castSnap = harness.messageSnapshot()
            castSpellByName("Burst Lightning").shouldBeTrue()

            // After cast: CastingTimeOptionsReq present, SelectTargetsReq absent
            val castMessages = harness.messagesSince(castSnap)
            castMessages.any { it.hasCastingTimeOptionsReq() }.shouldBeTrue()
            castMessages.any { it.hasSelectTargetsReq() }.shouldBeFalse()

            // After responding to optional cost: SelectTargetsReq appears
            val targetSnap = harness.messageSnapshot()
            declineKicker()
            val targetMessages = harness.messagesSince(targetSnap)
            targetMessages.any { it.hasSelectTargetsReq() }.shouldBeTrue()
        }
    })
