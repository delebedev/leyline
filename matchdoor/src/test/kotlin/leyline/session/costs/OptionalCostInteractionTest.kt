package leyline.session.costs

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

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

            val cto =
                after { castSpellByName("Burst Lightning").shouldBeTrue() }
                    .expectCastingTimeOptionsReq {
                        option(CastingTimeOptionType.Kicker, ctoId = 1)
                        done(ctoId = 0, required = true)
                    }

            // Locks the option count: block-form asserts Kicker + Done are present
            // and shaped correctly, but a third unexpected option would slip through.
            cto.castingTimeOptionReqList shouldHaveSize 2
        }

        test("kicked Burst Lightning deals 4 damage") {
            startBurst()

            castSpellByName("Burst Lightning").shouldBeTrue()
            acceptKicker()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            ai.life shouldBe 16
        }

        test("accepted kicker emits CastingTimeOption Kicker details") {
            startBurst()
            val burstGrpId = harness.bridge.cardRepository.findGrpIdByName("Burst Lightning")!!
            val kickerAbilityGrpId =
                harness.bridge.cardRepository.findKeywordAbilityGrpId(burstGrpId, KeywordAbilityIds.KICKER)!!

            val snap = messageSnapshot()
            castSpellByName("Burst Lightning").shouldBeTrue()
            acceptKicker()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            val messages = messagesSince(snap)
            val ctos =
                messages
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .filter { it.detailInt("type") == CastingTimeOptionType.Kicker.number }
            ctos shouldHaveSize 1
            val cto = ctos.single()

            assertSoftly {
                cto.detailsList.map { it.key }.toSet() shouldBe setOf("type", "kickerAbilityGrpId")
                cto.detailInt("kickerAbilityGrpId") shouldBe kickerAbilityGrpId
            }
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

            val cast = after { castSpellByName("Burst Lightning").shouldBeTrue() }

            // GSM immediately before the CTO should NOT carry a synthesized
            // ability — kicker is a spell-time cost, not an ETB trigger.
            val ctoIdx = cast.messages.indexOfFirst { it.hasCastingTimeOptionsReq() }
            val gsmBeforeCto = cast.messages[ctoIdx - 1].gameStateMessage
            gsmBeforeCto.gameObjectsList.filter { it.type == GameObjectType.Ability } shouldHaveSize 0
        }

        test("optional cost prompt gates targeting — no SelectTargetsReq before response") {
            startBurst()

            val cast = after { castSpellByName("Burst Lightning").shouldBeTrue() }
            cast.expectOneCastingTimeOptionsReq().castingTimeOptionReqList shouldHaveSize 2
            cast.expectNoSelectTargetsReq()

            after { declineKicker() }.expectOneSelectTargetsReq()
        }

        test("cancel optional cost prompt returns to priority without orphaning cast") {
            startBurst()

            after { castSpellByName("Burst Lightning").shouldBeTrue() }
                .expectOneCastingTimeOptionsReq()

            val cancel = after { cancelAction() }

            cancel.messages.any { it.hasActionsAvailableReq() } shouldBe true
            human
                .getZone(ForgeZoneType.Hand)
                .cards
                .map { it.name } shouldContain "Burst Lightning"
        }
    })
