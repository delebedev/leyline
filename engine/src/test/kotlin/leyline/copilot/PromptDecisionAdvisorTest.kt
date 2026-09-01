package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.DeclareBlockersReq
import wotc.mtgo.gre.external.messaging.Messages.EffectCostReq
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Group
import wotc.mtgo.gre.external.messaging.Messages.GroupReq
import wotc.mtgo.gre.external.messaging.Messages.GroupSpecification
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.SearchFromGroupsReq
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SubZoneType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/** Pins typed advisor failure visibility without requiring a live Forge game. */
@Suppress("MissingAssertSoftly")
class PromptDecisionAdvisorTest :
    FunSpec({
        tags(UnitTag)

        test("unknown prompt is unavailable with an explicit unsupported reason") {
            val advisor = PromptDecisionAdvisor(ForgeAiPolicy({ error("unused") }, leyline.bridge.types.SeatId(1)))

            val result =
                advisor.decide(
                    wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
                        .getDefaultInstance(),
                )

            val unavailable = result.shouldBeInstanceOf<PromptDecisionResult.Unavailable>()
            unavailable.reason shouldBe PromptUnavailableReason.UnsupportedPrompt
            unavailable.detail shouldBe "no advisor route for None_aa0d"
        }

        test("forced effect cost remains payable without reconstructed Forge cost context") {
            val advisor = PromptDecisionAdvisor(ForgeAiPolicy({ error("no live cost context") }, leyline.bridge.types.SeatId(1)))
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.PayCostsReq_695e)
                    .setPayCostsReq(
                        wotc.mtgo.gre.external.messaging.Messages.PayCostsReq
                            .newBuilder()
                            .setEffectCostReq(
                                EffectCostReq
                                    .newBuilder()
                                    .setEffectCostType(EffectCostType.Select_a59c)
                                    .setCostSelection(
                                        SelectNReq
                                            .newBuilder()
                                            .setMinSel(1)
                                            .setMaxSel(1)
                                            .setMinWeight(Int.MIN_VALUE)
                                            .setMaxWeight(Int.MAX_VALUE)
                                            .addIds(226)
                                            .addWeights(1),
                                    ),
                            ),
                    ).build()

            val chosen = advisor.decide(prompt).shouldBeInstanceOf<PromptDecisionResult.Chosen>()
            chosen.source shouldBe PromptDecisionSource.Default
            chosen.forgeAiAttempted shouldBe true
            chosen.decision shouldBe SimDecision.EffectCost(listOf(226))
        }

        test("prompt-derived decisions reject incomplete or constrained legal domains") {
            val groupedSearch =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.SearchFromGroupsReq_695e)
                    .setSearchFromGroupsReq(
                        SearchFromGroupsReq
                            .newBuilder()
                            .setMinFind(2)
                            .setMaxFind(2)
                            .addGroups(
                                Group
                                    .newBuilder()
                                    .setGroupId(7)
                                    .setMaxSelect(1)
                                    .addIds(101),
                            ),
                    ).build()
            PromptDecisionValidator.isValid(groupedSearch, DefaultDecisions.groupedSearch(groupedSearch)) shouldBe false

            val londonGroup =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.GroupReq_695e)
                    .setGroupReq(
                        GroupReq
                            .newBuilder()
                            .addAllInstanceIds((101..107).toList())
                            .addGroupSpecs(
                                GroupSpecification
                                    .newBuilder()
                                    .setLowerBound(6)
                                    .setUpperBound(6)
                                    .setZoneType(ZoneType.Hand)
                                    .setSubZoneType(SubZoneType.Top),
                            ).addGroupSpecs(
                                GroupSpecification
                                    .newBuilder()
                                    .setLowerBound(1)
                                    .setUpperBound(1)
                                    .setZoneType(ZoneType.Library)
                                    .setSubZoneType(SubZoneType.Bottom),
                            ).setContext(GroupingContext.LondonMulligan),
                    ).build()
            PromptDecisionValidator.isValid(londonGroup, DefaultDecisions.group(londonGroup)) shouldBe false

            val requiredBlock =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.DeclareBlockersReq_695e)
                    .setDeclareBlockersReq(DeclareBlockersReq.newBuilder().setHasRequirements(true))
                    .build()
            PromptDecisionValidator.isValid(requiredBlock, SimDecision.DeclareNoBlockers) shouldBe false

            val requiredCastingChoice =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.CastingTimeOptionsReq_695e)
                    .setCastingTimeOptionsReq(
                        CastingTimeOptionsReq
                            .newBuilder()
                            .addCastingTimeOptionReq(
                                CastingTimeOptionReq
                                    .newBuilder()
                                    .setCtoId(2)
                                    .setCastingTimeOptionType(CastingTimeOptionType.ChooseOrCost)
                                    .setIsRequired(true),
                            ),
                    ).build()
            PromptDecisionValidator.isValid(requiredCastingChoice, SimDecision.OptionalCost(ctoId = 0)) shouldBe false
        }
    })
