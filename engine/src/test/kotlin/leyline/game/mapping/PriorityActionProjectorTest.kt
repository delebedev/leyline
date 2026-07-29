package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.UnitTag
import leyline.bridge.handoff.ActionToken
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.ManaRequirementValue
import leyline.game.PreparedPriorityOffer
import leyline.game.PreparedPriorityWindow
import leyline.game.PriorityActionSet
import leyline.game.PriorityActionValue
import leyline.game.PriorityAutoTapActionValue
import leyline.game.PriorityAutoTapSolutionValue
import leyline.game.PriorityCastKind
import leyline.game.PriorityManaColor
import leyline.game.PriorityManaColorCountValue
import leyline.game.PriorityManaInfoValue
import leyline.game.PriorityManaPaymentOptionValue
import leyline.game.PriorityManaSelectionOptionValue
import leyline.game.PriorityManaSelectionValidation
import leyline.game.PriorityManaSelectionValue
import leyline.game.PriorityManaSpec
import leyline.game.PriorityPlayKind
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AutoTapAction
import wotc.mtgo.gre.external.messaging.Messages.AutoTapSolution
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo
import wotc.mtgo.gre.external.messaging.Messages.ManaPaymentOption
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement
import wotc.mtgo.gre.external.messaging.Messages.ManaSelection
import wotc.mtgo.gre.external.messaging.Messages.ManaSelectionOption
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType

class PriorityActionProjectorTest :
    FunSpec({
        tags(UnitTag)

        val ids =
            mapOf(
                ForgeCardId(10) to InstanceId(101),
                ForgeCardId(11) to InstanceId(202),
                ForgeCardId(12) to InstanceId(303),
                ForgeCardId(13) to InstanceId(404),
            )
        val resolve: (ForgeCardId) -> InstanceId = { checkNotNull(ids[it]) }

        test("projects cast semantic references with byte parity") {
            val value =
                PriorityActionValue.Cast(
                    kind = PriorityCastKind.CAST,
                    cardId = ForgeCardId(10),
                    grpId = 700,
                    facetCardId = ForgeCardId(11),
                    abilityGrpId = 701,
                    sourceCardId = ForgeCardId(12),
                    alternativeGrpId = 702,
                    manaCost = listOf(ManaRequirementValue(listOf(ManaColor.Red_afc9.number), 2, 701)),
                    shouldStop = true,
                    alternativeSourceCardId = ForgeCardId(13),
                )
            val expected =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Cast)
                    .setInstanceId(101)
                    .setGrpId(700)
                    .setFacetId(202)
                    .setAbilityGrpId(701)
                    .setSourceId(303)
                    .setAlternativeGrpId(702)
                    .addManaCost(
                        ManaRequirement
                            .newBuilder()
                            .addColor(ManaColor.Red_afc9)
                            .setCount(2)
                            .setAbilityGrpId(701),
                    ).setShouldStop(true)
                    .setAlternativeSourceZcid(404)
                    .build()

            PriorityActionProjector.project(value, resolve).toByteArray() shouldBe expected.toByteArray()
        }

        test("projects nested auto-tap source references with byte parity") {
            val manaInfo =
                PriorityManaInfoValue(
                    manaId = 10,
                    color = PriorityManaColor.GREEN,
                    sourceCardId = ForgeCardId(11),
                    specs = setOf(PriorityManaSpec.PREDICTIVE),
                    abilityGrpId = 55,
                    count = 1,
                )
            val payment = PriorityManaPaymentOptionValue(listOf(manaInfo))
            val value =
                PriorityActionValue.Activate(
                    cardId = ForgeCardId(10),
                    grpId = 600,
                    shouldStop = true,
                    autoTapSolution =
                        PriorityAutoTapSolutionValue(
                            listOf(PriorityAutoTapActionValue(ForgeCardId(11), 55, payment)),
                        ),
                )
            val expectedMana =
                ManaInfo
                    .newBuilder()
                    .setManaId(10)
                    .setColor(ManaColor.Green_afc9)
                    .setSrcInstanceId(202)
                    .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                    .setAbilityGrpId(55)
                    .setCount(1)
            val expectedPayment = ManaPaymentOption.newBuilder().addMana(expectedMana)
            val expected =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Activate_add3)
                    .setInstanceId(101)
                    .setGrpId(600)
                    .setFacetId(101)
                    .setShouldStop(true)
                    .setAutoTapSolution(
                        AutoTapSolution
                            .newBuilder()
                            .addAutoTapActions(
                                AutoTapAction
                                    .newBuilder()
                                    .setInstanceId(202)
                                    .setAbilityGrpId(55)
                                    .setManaPaymentOption(expectedPayment),
                            ),
                    ).build()

            PriorityActionProjector.project(value, resolve).toByteArray() shouldBe expected.toByteArray()
        }

        test("projects distinct activate-mana identity domains with byte parity") {
            val manaInfo =
                PriorityManaInfoValue(
                    manaId = 10,
                    color = PriorityManaColor.BLUE,
                    sourceCardId = ForgeCardId(11),
                    specs = setOf(PriorityManaSpec.PREDICTIVE),
                    abilityGrpId = 77,
                    count = 1,
                )
            val value =
                PriorityActionValue.ActivateMana(
                    cardId = ForgeCardId(10),
                    grpId = 800,
                    abilityGrpId = 77,
                    uniqueAbilityId = 51,
                    manaPaymentOptions = listOf(PriorityManaPaymentOptionValue(listOf(manaInfo))),
                    manaSelections =
                        listOf(
                            PriorityManaSelectionValue(
                                cardId = ForgeCardId(12),
                                abilityGrpId = 77,
                                selectionCount = 1,
                                validation = PriorityManaSelectionValidation.NON_REPEATABLE,
                                options =
                                    listOf(
                                        PriorityManaSelectionOptionValue(
                                            selectedColor = PriorityManaColor.BLUE,
                                            mana = listOf(PriorityManaColorCountValue(PriorityManaColor.BLUE, 1)),
                                        ),
                                    ),
                            ),
                        ),
                    batchable = true,
                )
            val expectedMana =
                ManaInfo
                    .newBuilder()
                    .setManaId(10)
                    .setColor(ManaColor.Blue_afc9)
                    .setSrcInstanceId(202)
                    .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                    .setAbilityGrpId(77)
                    .setCount(1)
            val expected =
                Action
                    .newBuilder()
                    .setActionType(ActionType.ActivateMana)
                    .setInstanceId(101)
                    .setGrpId(800)
                    .setFacetId(101)
                    .setAbilityGrpId(77)
                    .setUniqueAbilityId(51)
                    .addManaPaymentOptions(ManaPaymentOption.newBuilder().addMana(expectedMana))
                    .addManaSelections(
                        ManaSelection
                            .newBuilder()
                            .setInstanceId(303)
                            .setAbilityGrpId(77)
                            .setSelectionCount(1)
                            .setValidationType(SelectionValidationType.NonRepeatable)
                            .addOptions(
                                ManaSelectionOption
                                    .newBuilder()
                                    .setSelectedColor(ManaColor.Blue_afc9)
                                    .addMana(
                                        wotc.mtgo.gre.external.messaging.Messages.ManaColorCount
                                            .newBuilder()
                                            .setColor(ManaColor.Blue_afc9)
                                            .setCount(1),
                                    ),
                            ),
                    ).setIsBatchable(true)
                    .build()

            PriorityActionProjector.project(value, resolve).toByteArray() shouldBe expected.toByteArray()
        }

        test("projects every sealed priority action family") {
            val cases =
                listOf(
                    PriorityActionValue.Cast(PriorityCastKind.CAST, ForgeCardId(10), shouldStop = false) to ActionType.Cast,
                    PriorityActionValue.Activate(ForgeCardId(10), shouldStop = false) to ActionType.Activate_add3,
                    PriorityActionValue.ActivateMana(ForgeCardId(10), 1, batchable = false) to ActionType.ActivateMana,
                    PriorityActionValue.PlayLand(PriorityPlayKind.LAND, ForgeCardId(10), shouldStop = false) to ActionType.Play_add3,
                    PriorityActionValue.TurnFaceUp(ForgeCardId(10), 1, emptyList(), false) to ActionType.SpecialTurnFaceUp_add3,
                    PriorityActionValue.Pass to ActionType.Pass,
                    PriorityActionValue.FloatMana to ActionType.FloatMana,
                )

            cases.forEach { (value, actionType) ->
                PriorityActionProjector.project(value, resolve).actionType shouldBe actionType
            }
        }

        test("terminal projection resolves each action once and offers reuse the projected action") {
            val cast = PriorityActionValue.Cast(PriorityCastKind.CAST, ForgeCardId(10), shouldStop = false)
            val activate = PriorityActionValue.Activate(ForgeCardId(11), shouldStop = false)
            val inactive = PriorityActionValue.PlayLand(PriorityPlayKind.LAND, ForgeCardId(12), shouldStop = false)
            val window =
                PreparedPriorityWindow(
                    actionId = "priority-1",
                    actions = PriorityActionSet(listOf(cast, activate), listOf(inactive)),
                    offers =
                        listOf(
                            PreparedPriorityOffer(cast, ActionToken("cast"), ForgeCardId(10), 2),
                            PreparedPriorityOffer(
                                activate,
                                ActionToken("activate"),
                                ForgeCardId(11),
                                3,
                                stackAbilityGrpId = 40,
                                forgeAbilityId = 50,
                            ),
                        ),
                )
            val resolved = mutableListOf<ForgeCardId>()

            val projection =
                PriorityActionProjector.project(window) { cardId ->
                    resolved += cardId
                    checkNotNull(ids[cardId])
                }

            assertSoftly {
                resolved shouldBe listOf(ForgeCardId(10), ForgeCardId(11), ForgeCardId(12))
                projection.offers[0].action shouldBeSameInstanceAs projection.actions.actionsList[0]
                projection.offers[1].action shouldBeSameInstanceAs projection.actions.actionsList[1]
                projection.offers.map { it.token } shouldBe listOf(ActionToken("cast"), ActionToken("activate"))
                projection.offers[1].stackAbilityGrpId shouldBe 40
                projection.offers[1].forgeAbilityId shouldBe 50
            }
        }
    })
