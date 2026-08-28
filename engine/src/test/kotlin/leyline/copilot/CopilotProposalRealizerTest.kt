package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Pins the SimDecision → CopilotProposal contract for every prompt family the
 * copilot proposal surface covers. Metadata resolution is faked so the
 * decision→intent mapping is exercised without a live game.
 */
@Suppress("MissingAssertSoftly")
class CopilotProposalRealizerTest :
    FunSpec({

        tags(UnitTag)

        // Fake resolver: echoes the id and tags a name so target/card wiring is visible.
        val resolve = EntityResolver { id -> EntityRef(instanceId = id, name = "card$id", zone = "Hand", ownerSeat = 1) }

        fun action(
            type: ActionType,
            instanceId: Int,
            grpId: Int = 0,
            abilityGrpId: Int = 0,
            alternativeGrpId: Int = 0,
        ): Action =
            Action
                .newBuilder()
                .setActionType(type)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setAbilityGrpId(abilityGrpId)
                .setAlternativeGrpId(alternativeGrpId)
                .build()

        val aar = GREMessageType.ActionsAvailableReq_695e

        test("play-land action → play_land intent with resolved card") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.PerformAction(action(ActionType.Play_add3, instanceId = 10, grpId = 100)),
                    aar,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "play_land"
            p.card.shouldNotBeNull().name shouldBe "card10"
            p.responseIds shouldBe listOf(10)
        }

        test("cancel action → cancel intent (a realizable back-out, not unrealizable)") {
            val p = CopilotProposalRealizer.realize(SimDecision.CancelAction, aar, seat = 1, resolve)
            p.intent shouldBe "cancel"
        }

        test("base cast action → cast intent, no alternative") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.PerformAction(action(ActionType.Cast, instanceId = 11, grpId = 200)),
                    aar,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "cast"
            p.alternativeGrpId shouldBe null
            p.responseIds shouldBe listOf(11)
        }

        test("Adventure cast action → cast_adventure intent") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.PerformAction(action(ActionType.CastAdventure, instanceId = 14, grpId = 201)),
                    aar,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "cast_adventure"
            p.responseIds shouldBe listOf(14)
        }

        test("alt-cost cast action → cast_mdfc intent carrying the alternative grpId") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.PerformAction(action(ActionType.Cast, instanceId = 12, grpId = 200, alternativeGrpId = 19573)),
                    aar,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "cast_mdfc"
            p.alternativeGrpId shouldBe 19573
        }

        test("activate action → activate intent with ability grpId") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.PerformAction(action(ActionType.Activate_add3, instanceId = 13, abilityGrpId = 777)),
                    aar,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "activate"
            p.abilityGrpId shouldBe 777
        }

        test("select-targets → target intent with resolved entities") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.SelectTargets(mapOf(0 to listOf(21, 22))),
                    GREMessageType.SelectTargetsReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "target"
            p.targets.map { it.instanceId } shouldBe listOf(21, 22)
            p.targetGroups shouldBe mapOf("0" to listOf(21, 22))
            p.responseIds shouldBe listOf(21, 22)
        }

        test("select-n → select_n intent") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.SelectN(listOf(31)),
                    GREMessageType.SelectNreq,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "select_n"
            p.responseIds shouldBe listOf(31)
        }

        test("effect-cost → pay_cost intent") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.EffectCost(listOf(41, 42)),
                    GREMessageType.PayCostsReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "pay_cost"
            p.responseIds shouldBe listOf(41, 42)
        }

        test("modal-choice → modal intent with grpIds") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.ModalChoice(ctoId = 3, selectedGrpIds = listOf(555)),
                    GREMessageType.CastingTimeOptionsReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "modal"
            p.ctoId shouldBe 3
            p.modalGrpIds shouldBe listOf(555)
            p.responseIds shouldBe listOf(555)
        }

        test("mana-type → mana_type intent with per-cto colors") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.ManaTypeChoices(listOf(9 to ManaColor.Green_afc9)),
                    GREMessageType.CastingTimeOptionsReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "mana_type"
            p.manaTypes shouldBe listOf(ManaTypeChoice(9, "Green_afc9"))
            p.responseIds shouldBe listOf(9)
        }

        test("optional-cost → optional_cost intent with ctoId") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.OptionalCost(ctoId = 7),
                    GREMessageType.CastingTimeOptionsReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "optional_cost"
            p.ctoId shouldBe 7
            p.responseIds shouldBe listOf(7)
        }

        test("numeric-input → numeric intent") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.NumericInput(value = 3),
                    GREMessageType.PromptReq,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "numeric"
            p.numericValue shouldBe 3
        }

        test("distribution → distribute intent") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.Distribution(linkedMapOf(300 to 1, 361 to 1)),
                    GREMessageType.DistributionReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "distribute"
            p.responseIds shouldBe listOf(300, 361)
        }

        test("casting-time X retains ctoId and numeric value") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.CastingTimeX(ctoId = 2, value = 4),
                    GREMessageType.CastingTimeOptionsReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "numeric"
            p.ctoId shouldBe 2
            p.numericValue shouldBe 4
        }

        test("optional-action → optional_action intent carrying accept/decline") {
            CopilotProposalRealizer
                .realize(SimDecision.OptionalAction(accept = true), GREMessageType.OptionalActionMessage_695e, seat = 1, resolve)
                .let {
                    it.intent shouldBe "optional_action"
                    it.accept shouldBe true
                }
            CopilotProposalRealizer
                .realize(SimDecision.OptionalAction(accept = false), GREMessageType.OptionalActionMessage_695e, seat = 1, resolve)
                .accept shouldBe false
        }

        test("declare-attackers → attack intent with resolved attackers") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.DeclareAttackers(listOf(51, 52)),
                    GREMessageType.DeclareAttackersReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "attack"
            p.targets.map { it.instanceId } shouldBe listOf(51, 52)
            p.responseIds shouldBe listOf(51, 52)
        }

        test("declare-all-attackers is unrealizable without deliverable bytes") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.DeclareAllAttackers,
                    GREMessageType.DeclareAttackersReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "unrealizable"
            p.responses shouldBe emptyList()
        }

        test("declare-blockers → block intent with blocker→attacker pairs") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.DeclareBlockers(mapOf(61 to 71)),
                    GREMessageType.DeclareBlockersReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "block"
            p.blocks
                .single()
                .blocker.instanceId shouldBe 61
            p.blocks
                .single()
                .attacker.instanceId shouldBe 71
            p.responseIds shouldBe listOf(61)
        }

        test("declare-no-blockers → block intent with no assignments") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.DeclareNoBlockers,
                    GREMessageType.DeclareBlockersReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "block"
            p.blocks shouldBe emptyList()
        }

        test("pass-priority → pass intent") {
            CopilotProposalRealizer
                .realize(SimDecision.PassPriority, aar, seat = 1, resolve)
                .intent shouldBe "pass"
        }

        test("search decodes to a search intent carrying the found ids") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.Search(listOf(1, 2)),
                    GREMessageType.SearchReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "search"
            p.responseIds shouldBe listOf(1, 2)
        }

        test("distribution decodes to a fixed-total intent carrying per-target amounts") {
            val p =
                CopilotProposalRealizer.realize(
                    SimDecision.Distribution(linkedMapOf(11 to 2, 12 to 3)),
                    GREMessageType.DistributionReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "distribute"
            p.distribution.map { it.instanceId to it.amount } shouldBe listOf(11 to 2, 12 to 3)
            p.responseIds shouldBe listOf(11, 12)
        }

        test("explicit unrealizable carries prompt type and reason") {
            val p = CopilotProposalRealizer.unrealizable(GREMessageType.SelectTargetsReq_695e, seat = 2, reason = "no game")
            p.intent shouldBe "unrealizable"
            p.promptType shouldBe "SelectTargetsReq_695e"
            p.seat shouldBe 2
            p.reason shouldBe "no game"
        }
    })
