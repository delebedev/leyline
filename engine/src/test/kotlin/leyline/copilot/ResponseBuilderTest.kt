package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ReplacementEffect
import wotc.mtgo.gre.external.messaging.Messages.TeamType

/**
 * Pins the injectable response bytes per decision. The combat cases guard the
 * two-round-trip contract: a `DeclareAttackers/BlockersResp` toggle answers the
 * current prompt, the engine echoes a fresh prompt, and a separate
 * `Submit…Req` — carrying that re-prompt's msgId in `respId` and its real
 * gsId — finalizes. Every message answers exactly one prompt.
 */
@Suppress("MissingAssertSoftly")
class ResponseBuilderTest :
    FunSpec({

        tags(UnitTag)

        fun decode(hex: String): List<ClientToGREMessage> =
            hex.split(",").map { part ->
                val bytes = ByteArray(part.length / 2) { i -> part.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                ClientToGREMessage.parseFrom(bytes)
            }

        fun bytesOf(
            decision: SimDecision,
            gsId: Int = 42,
            seat: Int = 1,
            respId: Int = 0,
        ) = decode(ResponseBuilder.hexMessages(ResponseBuilder.build(decision, gsId, seat, respId)).single())

        test("pass is a single PerformActionResp Pass tagged to the prompt gsId") {
            val msgs = bytesOf(SimDecision.PassPriority)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.PerformActionResp_097b
            msgs[0].gameStateId shouldBe 42
            msgs[0]
                .performActionResp.actionsList
                .single()
                .actionType shouldBe ActionType.Pass
        }

        test("cancel action is a lone CancelActionReq tagged to the prompt") {
            // Backs out an in-flight action the copilot cannot complete (e.g. a
            // PayCostsReq it cannot realize) so the game-loop unwinds to priority
            // instead of parking.
            val msgs = bytesOf(SimDecision.CancelAction, gsId = 88, respId = 231)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.CancelActionReq_097b
            msgs[0].gameStateId shouldBe 88
            msgs[0].respId shouldBe 231
        }

        test("declare attackers selection names the opponent as damage recipient") {
            val msgs = bytesOf(SimDecision.DeclareAttackers(listOf(263)), respId = 111)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.DeclareAttackersResp_097b
            msgs[0].gameStateId shouldBe 42
            msgs[0].respId shouldBe 111
            val attacker = msgs[0].declareAttackersResp.selectedAttackersList.single()
            attacker.attackerInstanceId shouldBe 263
            // The recipient is what commits the attack on the real host.
            attacker.selectedDamageRecipient.playerSystemSeatId shouldBe 2
        }

        test("submit attackers answers the re-prompt with its msgId and real gsId") {
            val msgs = bytesOf(SimDecision.SubmitAttackers, gsId = 85, respId = 113)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.SubmitAttackersReq
            msgs[0].gameStateId shouldBe 85
            msgs[0].respId shouldBe 113
        }

        test("declare blockers is a lone assignment toggle answering the prompt") {
            val msgs = bytesOf(SimDecision.DeclareBlockers(mapOf(10 to 20)), respId = 221)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.DeclareBlockersResp_097b
            msgs[0].respId shouldBe 221
            val blocker = msgs[0].declareBlockersResp.selectedBlockersList.single()
            blocker.blockerInstanceId shouldBe 10
            blocker.selectedAttackerInstanceIdsList.single() shouldBe 20
        }

        test("undeclare blocker sends an empty-selection entry for that blocker") {
            val msgs = bytesOf(SimDecision.UndeclareBlocker(10), respId = 224)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.DeclareBlockersResp_097b
            msgs[0].respId shouldBe 224
            val blocker = msgs[0].declareBlockersResp.selectedBlockersList.single()
            blocker.blockerInstanceId shouldBe 10
            blocker.selectedAttackerInstanceIdsCount shouldBe 0
        }

        test("submit blockers answers the pending prompt with its msgId and real gsId") {
            val msgs = bytesOf(SimDecision.SubmitBlockers, gsId = 90, respId = 226)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.SubmitBlockersReq
            msgs[0].gameStateId shouldBe 90
            msgs[0].respId shouldBe 226
        }

        test("declare no blockers submits an empty selection against the current prompt") {
            val msgs = bytesOf(SimDecision.DeclareNoBlockers, respId = 200)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.SubmitBlockersReq
            msgs[0].gameStateId shouldBe 42
            msgs[0].respId shouldBe 200
        }

        test("select-n serializes the chosen instance ids") {
            val msgs = bytesOf(SimDecision.SelectN(listOf(5, 9)))
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.SelectNresp
            msgs[0].selectNResp.idsList shouldBe listOf(5, 9)
        }

        test("modal choose-one serializes the picked grpIds as a CastingTimeOptions modal") {
            val msgs = bytesOf(SimDecision.ModalChoice(ctoId = 3, selectedGrpIds = listOf(101, 202)))
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.CastingTimeOptionsResp_097b
            msgs[0].castingTimeOptionsResp.castingTimeOptionResp.ctoId shouldBe 3
            msgs[0]
                .castingTimeOptionsResp.castingTimeOptionResp.chooseModalResp.grpIdsList shouldBe listOf(101, 202)
        }

        test("select targets is a Select-marked pick carrying the group targetIdx") {
            val msgs = bytesOf(SimDecision.SelectTargets(mapOf(1 to listOf(282))), respId = 93)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.SelectTargetsResp_097b
            msgs[0].respId shouldBe 93
            msgs[0].selectTargetsResp.target.targetIdx shouldBe 1
            val t =
                msgs[0]
                    .selectTargetsResp.target.targetsList
                    .single()
            t.targetInstanceId shouldBe 282
            t.legalAction shouldBe wotc.mtgo.gre.external.messaging.Messages.SelectAction.Select_a1ad
        }

        test("unselect targets marks the pick Unselect") {
            val msgs = bytesOf(SimDecision.UnselectTargets(mapOf(0 to listOf(283))), respId = 94)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.SelectTargetsResp_097b
            val t =
                msgs[0]
                    .selectTargetsResp.target.targetsList
                    .single()
            t.targetInstanceId shouldBe 283
            t.legalAction shouldBe wotc.mtgo.gre.external.messaging.Messages.SelectAction.Unselect
        }

        test("submit targets answers the re-prompt with its msgId and real gsId") {
            val msgs = bytesOf(SimDecision.SubmitTargets, gsId = 88, respId = 95)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.SubmitTargetsReq
            msgs[0].gameStateId shouldBe 88
            msgs[0].respId shouldBe 95
        }

        test("keep hand answers the mulligan with AcceptHand") {
            val msgs = bytesOf(SimDecision.KeepHand, respId = 3)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.MulliganResp_097b
            msgs[0].respId shouldBe 3
            msgs[0].mulliganResp.decision shouldBe
                wotc.mtgo.gre.external.messaging.Messages.MulliganOption.AcceptHand
        }

        test("starting-player response chooses the requesting seat") {
            val msgs = bytesOf(SimDecision.ChooseStartingPlayer, gsId = 1, seat = 2, respId = 5)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.ChooseStartingPlayerResp_097b
            msgs[0].gameStateId shouldBe 1
            msgs[0].respId shouldBe 5
            msgs[0].chooseStartingPlayerResp.teamType shouldBe TeamType.Individual
            msgs[0].chooseStartingPlayerResp.systemSeatId shouldBe 2
            msgs[0].chooseStartingPlayerResp.teamId shouldBe 2
        }

        test("auto-tap payment confirms the offered solution by index") {
            val msgs = bytesOf(SimDecision.AutoTapPayment(0), respId = 125)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.PerformAutoTapActionsResp_097b
            msgs[0].respId shouldBe 125
            msgs[0].performAutoTapActionsResp.index shouldBe 0
        }

        test("grouped search echoes one group row with selected ids") {
            val msgs = bytesOf(SimDecision.GroupedSearch(groupId = 5004, itemsFound = listOf(105), maxSelect = 1), respId = 77)
            msgs.single().type shouldBe ClientMessageType.SearchFromGroupsResp_097b
            msgs.single().respId shouldBe 77
            msgs.single().gameStateId shouldBe 42
            val group =
                msgs
                    .single()
                    .searchFromGroupsResp.groupsList
                    .single()
            group.groupId shouldBe 5004
            group.maxSelect shouldBe 1
            group.idsList shouldBe listOf(105)
        }

        test("replacement echoes the complete identity-rich row") {
            val row =
                ReplacementEffect
                    .newBuilder()
                    .setObjectInstance(7)
                    .setAffectedObject(7)
                    .setUniqueAbilityId(101)
                    .setAbilityGrpId(202)
                    .setReplacementEffectId(9000)
                    .build()
            val msg = bytesOf(SimDecision.SelectReplacement(row), gsId = 44, respId = 78).single()
            msg.type shouldBe ClientMessageType.SelectReplacementResp_097b
            msg.gameStateId shouldBe 44
            msg.respId shouldBe 78
            msg.selectReplacementResp.replacement shouldBe row
        }

        test("optional cost decline sends the Done option by type (no ctoId)") {
            val msgs = bytesOf(SimDecision.OptionalCost(0), respId = 803)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.CastingTimeOptionsResp_097b
            val cto = msgs[0].castingTimeOptionsResp.castingTimeOptionResp
            cto.castingTimeOptionType shouldBe wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType.Done
            cto.ctoId shouldBe 0
        }

        test("optional cost accept carries the ctoId and Kicker type") {
            val msgs = bytesOf(SimDecision.OptionalCost(2))
            val cto = msgs[0].castingTimeOptionsResp.castingTimeOptionResp
            cto.ctoId shouldBe 2
            cto.castingTimeOptionType shouldBe wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType.Kicker
        }

        test("numeric input serializes the chosen value") {
            val msgs = bytesOf(SimDecision.NumericInput(3))
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.NumericInputResp_097b
            msgs[0].numericInputResp.numericInputValue shouldBe 3
        }

        test("casting-time X serializes inside the casting option response") {
            val msgs = bytesOf(SimDecision.CastingTimeX(ctoId = 2, value = 4))
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.CastingTimeOptionsResp_097b
            val cto = msgs[0].castingTimeOptionsResp.castingTimeOptionResp
            cto.ctoId shouldBe 2
            cto.castingTimeOptionType shouldBe wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType.ChooseX_a7b4
            cto.numericInputResp.numericInputValue shouldBe 4
        }

        test("distribution serializes each target amount against the prompt") {
            val msgs = bytesOf(SimDecision.Distribution(linkedMapOf(300 to 1, 361 to 1)), gsId = 392, respId = 544)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.DistributionResp_097b
            msgs[0].gameStateId shouldBe 392
            msgs[0].respId shouldBe 544
            msgs[0].distributionResp.distributionsList.map { it.instanceId to it.amount } shouldBe
                listOf(300 to 1, 361 to 1)
        }

        test("assign damage echoes each attacker's (target, damage) pairs") {
            val decision =
                SimDecision.AssignDamage(
                    listOf(
                        SimDecision.DamageAssignerDecision(
                            instanceId = 263,
                            totalDamage = 5,
                            assignments =
                                listOf(
                                    SimDecision.DamageAssignmentDecision(400, minDamage = 3, maxDamage = 5, assignedDamage = 3),
                                    SimDecision.DamageAssignmentDecision(401, minDamage = 2, maxDamage = 5, assignedDamage = 2),
                                ),
                        ),
                    ),
                )
            val msgs = bytesOf(decision)
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.AssignDamageResp_097b
            val assigner = msgs[0].assignDamageResp.assignersList.single()
            assigner.instanceId shouldBe 263
            assigner.totalDamage shouldBe 5
            assigner.assignmentsList.map {
                listOf(it.instanceId, it.minDamage, it.maxDamage, it.assignedDamage)
            } shouldBe listOf(listOf(400, 3, 5, 3), listOf(401, 2, 5, 2))
        }

        test("scry keep-on-top puts every id in the top group and nothing on the bottom") {
            val msgs = bytesOf(SimDecision.GroupTop(listOf(1, 2, 3)))
            msgs.size shouldBe 1
            msgs[0].type shouldBe ClientMessageType.GroupResp_097b
            val groups = msgs[0].groupResp.groupsList
            groups[0].idsList shouldBe listOf(1, 2, 3)
            groups[1].idsList.shouldBeEmpty()
        }
    })
