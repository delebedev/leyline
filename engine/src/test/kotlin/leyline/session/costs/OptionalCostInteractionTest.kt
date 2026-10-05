package leyline.session.costs

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.battlefield
import leyline.testkit.detailInt
import leyline.testkit.hand
import leyline.testkit.persistentAnnotationsOfType
import leyline.tooling.headless.clientMessage
import leyline.tooling.headless.optionalCostResp
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

        /** Accept kicker — send the Kicker option's ctoId. */
        fun MatchFlowHarness.acceptKicker() {
            val kickerOption =
                lastCastingTimeOptionsReq().castingTimeOptionReqList.first {
                    it.castingTimeOptionType == CastingTimeOptionType.Kicker
                }
            respondToOptionalCost(kickerOption.ctoId)
        }

        /** Decline kicker — send the Done option's ctoId (0). */
        fun MatchFlowHarness.declineKicker() {
            val doneOption =
                lastCastingTimeOptionsReq().castingTimeOptionReqList.first {
                    it.castingTimeOptionType == CastingTimeOptionType.Done
                }
            respondToOptionalCost(doneOption.ctoId)
        }

        session(
            "explicitly kicked Into the Roil bounces and draws",
            puzzle =
                burstState
                    .replace("Burst Lightning", "Into the Roil")
                    .replace("Mountain", "Island"),
        ) {
            val handSize = human.getZone(forge.game.zone.ZoneType.Hand).size()
            castSpellByName("Into the Roil").shouldBeTrue()
            acceptKicker()
            selectTargets(listOf(ai.battlefield.iid("Centaur Courser")))
            passUntilResolved()

            assertSoftly {
                ai.hand.card("Centaur Courser").name shouldBe "Centaur Courser"
                human.getZone(forge.game.zone.ZoneType.Hand).size() shouldBe handSize
            }
        }

        session("CastingTimeOptionsReq — kicker prompt shape", puzzle = burstState) {
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

        session("kicked Burst Lightning deals 4 damage", puzzle = burstState) {
            castSpellByName("Burst Lightning").shouldBeTrue()
            acceptKicker()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            ai.life shouldBe 16
        }

        session("accepted kicker emits CastingTimeOption Kicker details", puzzle = burstState) {
            val burstGrpId = bridge.cardRepository.findGrpIdByName("Burst Lightning")!!
            val kickerAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(burstGrpId, KeywordAbilityIds.KICKER)!!

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

        session("unkicked Burst Lightning deals 2 damage", puzzle = burstState) {
            castSpellByName("Burst Lightning").shouldBeTrue()
            declineKicker()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            ai.life shouldBe 18
        }

        session("kicker GSM has no synthesized ability on stack", puzzle = burstState) {
            val cast = after { castSpellByName("Burst Lightning").shouldBeTrue() }

            // GSM immediately before the CTO should NOT carry a synthesized
            // ability — kicker is a spell-time cost, not an ETB trigger.
            val ctoIdx = cast.messages.indexOfFirst { it.hasCastingTimeOptionsReq() }
            val gsmBeforeCto = cast.messages[ctoIdx - 1].gameStateMessage
            gsmBeforeCto.gameObjectsList.filter { it.type == GameObjectType.Ability } shouldHaveSize 0
        }

        session("optional cost prompt gates targeting — no SelectTargetsReq before response", puzzle = burstState) {
            val cast = after { castSpellByName("Burst Lightning").shouldBeTrue() }
            cast.expectOneCastingTimeOptionsReq().castingTimeOptionReqList shouldHaveSize 2
            cast.expectNoSelectTargetsReq()

            after { declineKicker() }.expectOneSelectTargetsReq()
        }

        session("invalid and stale optional responses leave the exact prompt answerable", puzzle = burstState) {
            after { castSpellByName("Burst Lightning").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            val promptGameStateId = allMessages.last { it.hasCastingTimeOptionsReq() }.gameStateId
            val stale = optionalCostResp(999).toBuilder().setGameStateId(promptGameStateId - 1).build()
            // These invalid responses intentionally have no owned successor.
            send(stale)
            send(optionalCostResp(999))
            drainSink()

            after { declineKicker() }.expectOneSelectTargetsReq().targetsList shouldHaveSize 1
        }

        session("cancel optional cost prompt returns to priority without orphaning cast", puzzle = burstState) {
            after { castSpellByName("Burst Lightning").shouldBeTrue() }
                .expectOneCastingTimeOptionsReq()

            val cancel = after { cancelAction() }

            cancel.messages.any { it.hasActionsAvailableReq() } shouldBe true
            human
                .getZone(ForgeZoneType.Hand)
                .cards
                .map { it.name } shouldContain "Burst Lightning"
        }
        for (count in listOf(0, 2)) {
            session(
                "multikicker count $count pays exactly the selected repetitions",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Joraga Warcaller
                    humanbattlefield=Forest;Forest;Forest;Forest;Forest;Llanowar Elves|Tapped
                    humanlibrary=Forest
                    ailibrary=Mountain
                    """.trimIndent(),
            ) {
                holdNextNumericInput()
                castSpellByName("Joraga Warcaller").shouldBeTrue()
                val numeric = allMessages.lastOrNull { it.hasNumericInputReq() }
                checkNotNull(numeric) { "Multikicker must publish a numeric choice" }
                assertSoftly {
                    numeric.numericInputReq.minValue shouldBe 0
                    numeric.numericInputReq.maxValue shouldBe 2
                    human.getZone(ForgeZoneType.Battlefield).cards.count { it.name == "Forest" && it.isTapped } shouldBe 0
                }
                if (count == 2) {
                    for (invalid in listOf(-1, 3)) {
                        val response =
                            clientMessage(ClientMessageType.NumericInputResp_097b) {
                                gameStateId = numeric.gameStateId
                                respId = numeric.msgId
                                setNumericInputResp(NumericInputResp.newBuilder().setNumericInputValue(invalid))
                            }
                        send(response)
                        drainSink()
                    }
                    human.getZone(ForgeZoneType.Battlefield).cards.count { it.name == "Forest" && it.isTapped } shouldBe 0
                }
                respondToNumericInput(count)
                passUntilResolved()
                assertSoftly {
                    human.battlefield.card("Joraga Warcaller").netPower shouldBe 1 + count
                    human.battlefield.card("Llanowar Elves").netPower shouldBe 1 + count
                    human.getZone(ForgeZoneType.Battlefield).cards.count { it.name == "Forest" && it.isTapped } shouldBe 1 + 2 * count
                }
            }
        }
        session(
            "replicate count two creates copies with independently selected targets",
            fullControl = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Shattering Spree;Memnite
                humanbattlefield=Mountain;Mountain;Mountain
                humanlibrary=Forest;Forest
                aibattlefield=Ornithopter;Gingerbrute;Goldvein Pick
                ailibrary=Mountain;Mountain
                """.trimIndent(),
        ) {
            holdNextNumericInput()
            castSpellByName("Shattering Spree").shouldBeTrue()
            allMessages.last { it.hasNumericInputReq() }.numericInputReq.maxValue shouldBe 2
            respondToNumericInput(2)
            selectTargets(listOf(ai.battlefield.iid("Ornithopter")))
            passPriority()
            val first = allMessages.lastOrNull { it.hasSelectTargetsReq() }
            checkNotNull(first) { "Replicate must offer targets for its first copy" }
            selectTargets(listOf(ai.battlefield.iid("Gingerbrute")))
            selectTargets(listOf(ai.battlefield.iid("Goldvein Pick")))
            passUntilResolved()
            assertSoftly {
                ai
                    .getZone(ForgeZoneType.Graveyard)
                    .cards
                    .map { it.name }
                    .toSet() shouldBe
                    setOf("Ornithopter", "Gingerbrute", "Goldvein Pick")
                human.getZone(ForgeZoneType.Battlefield).cards.count { it.isTapped } shouldBe 3
                human.life shouldBe 20
            }
            castSpellByName("Memnite").shouldBeTrue()
            passUntilResolved()
            human.battlefield.card("Memnite").name shouldBe "Memnite"
        }
    })
