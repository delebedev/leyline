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
class ProposalTranslatorTest :
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
                ProposalTranslator.translate(
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
            val p = ProposalTranslator.translate(SimDecision.CancelAction, aar, seat = 1, resolve)
            p.intent shouldBe "cancel"
        }

        test("base cast action → cast intent, no alternative") {
            val p =
                ProposalTranslator.translate(
                    SimDecision.PerformAction(action(ActionType.Cast, instanceId = 11, grpId = 200)),
                    aar,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "cast"
            p.alternativeGrpId shouldBe null
            p.responseIds shouldBe listOf(11)
        }

        test("alt-cost cast action → cast_mdfc intent carrying the alternative grpId") {
            val p =
                ProposalTranslator.translate(
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
                ProposalTranslator.translate(
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
                ProposalTranslator.translate(
                    SimDecision.SelectTargets(listOf(21, 22)),
                    GREMessageType.SelectTargetsReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "target"
            p.targets.map { it.instanceId } shouldBe listOf(21, 22)
            p.responseIds shouldBe listOf(21, 22)
        }

        test("select-n → select_n intent") {
            val p =
                ProposalTranslator.translate(
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
                ProposalTranslator.translate(
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
                ProposalTranslator.translate(
                    SimDecision.ModalChoice(listOf(555)),
                    GREMessageType.CastingTimeOptionsReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "modal"
            p.modalGrpIds shouldBe listOf(555)
            p.responseIds shouldBe listOf(555)
        }

        test("mana-type → mana_type intent with per-cto colors") {
            val p =
                ProposalTranslator.translate(
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
                ProposalTranslator.translate(
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
                ProposalTranslator.translate(
                    SimDecision.NumericInput(value = 3),
                    GREMessageType.PromptReq,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "numeric"
            p.numericValue shouldBe 3
        }

        test("optional-action → optional_action intent carrying accept/decline") {
            ProposalTranslator
                .translate(SimDecision.OptionalAction(accept = true), GREMessageType.OptionalActionMessage_695e, seat = 1, resolve)
                .let {
                    it.intent shouldBe "optional_action"
                    it.accept shouldBe true
                }
            ProposalTranslator
                .translate(SimDecision.OptionalAction(accept = false), GREMessageType.OptionalActionMessage_695e, seat = 1, resolve)
                .accept shouldBe false
        }

        test("declare-attackers → attack intent with resolved attackers") {
            val p =
                ProposalTranslator.translate(
                    SimDecision.DeclareAttackers(listOf(51, 52)),
                    GREMessageType.DeclareAttackersReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "attack"
            p.targets.map { it.instanceId } shouldBe listOf(51, 52)
            p.responseIds shouldBe listOf(51, 52)
        }

        test("declare-all-attackers → attack_all intent") {
            ProposalTranslator
                .translate(SimDecision.DeclareAllAttackers, GREMessageType.DeclareAttackersReq_695e, seat = 1, resolve)
                .intent shouldBe "attack_all"
        }

        test("declare-blockers → block intent with blocker→attacker pairs") {
            val p =
                ProposalTranslator.translate(
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
                ProposalTranslator.translate(
                    SimDecision.DeclareNoBlockers,
                    GREMessageType.DeclareBlockersReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "block"
            p.blocks shouldBe emptyList()
        }

        test("pass-priority → pass intent") {
            ProposalTranslator
                .translate(SimDecision.PassPriority, aar, seat = 1, resolve)
                .intent shouldBe "pass"
        }

        test("search decodes to a search intent carrying the found ids") {
            val p =
                ProposalTranslator.translate(
                    SimDecision.Search(listOf(1, 2)),
                    GREMessageType.SearchReq_695e,
                    seat = 1,
                    resolve,
                )
            p.intent shouldBe "search"
            p.responseIds shouldBe listOf(1, 2)
        }

        test("truly unmapped decision families → unrealizable with a reason") {
            val p = ProposalTranslator.translate(SimDecision.RetirePrompt, GREMessageType.PromptReq, seat = 1, resolve)
            p.intent shouldBe "unrealizable"
            p.reason.shouldNotBeNull()
        }

        test("explicit unrealizable carries prompt type and reason") {
            val p = ProposalTranslator.unrealizable(GREMessageType.SelectTargetsReq_695e, seat = 2, reason = "no game")
            p.intent shouldBe "unrealizable"
            p.promptType shouldBe "SelectTargetsReq_695e"
            p.seat shouldBe 2
            p.reason shouldBe "no game"
        }
    })
