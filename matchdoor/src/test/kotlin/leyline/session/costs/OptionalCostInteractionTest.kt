package leyline.session.costs

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.gsm
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
    SessionTest({

        val burstState =
            """
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

        fun startBurst() = startPuzzle(burstState, name = "Burst Lightning")

        /** Accept kicker — send the Kicker option's ctoId. */
        fun acceptKicker() {
            val kickerOption =
                lastCastingTimeOptionsReq().castingTimeOptionReqList.first {
                    it.castingTimeOptionType == CastingTimeOptionType.Kicker
                }
            respondToOptionalCost(kickerOption.ctoId)
        }

        /** Decline kicker — send the Done option's ctoId (0). */
        fun declineKicker() {
            val doneOption =
                lastCastingTimeOptionsReq().castingTimeOptionReqList.first {
                    it.castingTimeOptionType == CastingTimeOptionType.Done
                }
            respondToOptionalCost(doneOption.ctoId)
        }

        test("CastingTimeOptionsReq — kicker prompt shape") {
            startBurst()

            val snap = messageSnapshot()
            castSpellByName("Burst Lightning").shouldBeTrue()
            val castMessages = messagesSince(snap)

            val ctoReq =
                castMessages
                    .first { it.hasCastingTimeOptionsReq() }
                    .castingTimeOptionsReq

            // Two options: Kicker + Done
            ctoReq.castingTimeOptionReqList shouldHaveSize 2

            val kickerOption =
                ctoReq.castingTimeOptionReqList.first {
                    it.castingTimeOptionType == CastingTimeOptionType.Kicker
                }
            val doneOption =
                ctoReq.castingTimeOptionReqList.first {
                    it.castingTimeOptionType == CastingTimeOptionType.Done
                }

            assertSoftly {
                kickerOption.ctoId shouldBe 1
                doneOption.ctoId shouldBe 0
                doneOption.isRequired.shouldBeTrue()
            }
        }

        test("kicked Burst Lightning deals 4 damage") {
            startBurst()

            castSpellByName("Burst Lightning").shouldBeTrue()
            acceptKicker()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            ai.life shouldBe 16
        }

        test("unkicked Burst Lightning deals 2 damage") {
            startBurst()

            castSpellByName("Burst Lightning").shouldBeTrue()
            declineKicker()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            ai.life shouldBe 18
        }

        test("kicker GSM has no synthesized ability on stack") {
            startBurst()

            val snap = messageSnapshot()
            castSpellByName("Burst Lightning").shouldBeTrue()
            val msgs = messagesSince(snap)

            val ctoIdx = msgs.indexOfFirst { it.hasCastingTimeOptionsReq() }
            ctoIdx shouldBeGreaterThan 0

            // GSM before CTO should NOT have a synthesized ability —
            // kicker is a spell-time cost, not an ETB trigger
            val gsm = msgs[ctoIdx - 1].gameStateMessage
            val abilities =
                gsm.gameObjectsList.filter {
                    it.type == GameObjectType.Ability
                }
            abilities shouldHaveSize 0
        }

        test("optional cost prompt gates targeting — no SelectTargetsReq before response") {
            startBurst()

            val castSnap = messageSnapshot()
            castSpellByName("Burst Lightning").shouldBeTrue()

            // After cast: CastingTimeOptionsReq present, SelectTargetsReq absent
            val castMessages = messagesSince(castSnap)
            castMessages.any { it.hasCastingTimeOptionsReq() }.shouldBeTrue()
            castMessages.any { it.hasSelectTargetsReq() }.shouldBeFalse()

            // After responding to optional cost: SelectTargetsReq appears
            val targetSnap = messageSnapshot()
            declineKicker()
            val targetMessages = messagesSince(targetSnap)
            targetMessages.any { it.hasSelectTargetsReq() }.shouldBeTrue()
        }
    })
