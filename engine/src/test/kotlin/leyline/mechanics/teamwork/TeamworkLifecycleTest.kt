package leyline.mechanics.teamwork

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.handoff.TapPaymentDescriptor
import leyline.bridge.handoff.TapPaymentKind
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.allGameObjects
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType

private val PUZZLE =
    """
    [metadata]
    Name:Teamwork - Timeline Inquiry
    Goal:Cast Timeline Inquiry using teamwork.
    Turns:2
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Timeline Inquiry
    humanbattlefield=Island;Island;Island;Island;Coral Merfolk|Counters:P1P1=1;Grizzly Bears;Goldfury Strider|Counters:M1M1=4
    humanlibrary=Island;Island;Island;Island
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

class TeamworkLifecycleTest :
    SessionTest({
        session("Timeline Inquiry pays Teamwork through CTO plus weighted PayCostsReq", puzzle = PUZZLE, validating = true) {
            val merfolkIid = human.battlefield.iid("Coral Merfolk")
            val bearsIid = human.battlefield.iid("Grizzly Bears")
            val striderIid = human.battlefield.iid("Goldfury Strider")
            val timelineGrpId = bridge.cardRepository.findGrpIdByName("Timeline Inquiry")!!
            val teamworkAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(timelineGrpId, KeywordAbilityIds.TEAMWORK)!!

            val cto =
                after { castSpellByName("Timeline Inquiry").shouldBeTrue() }
                    .expectOneCastingTimeOptionsReq()
            val teamworkOption =
                cto.castingTimeOptionReqList.single {
                    it.castingTimeOptionType == CastingTimeOptionType.AdditionalCost
                }
            val ctoSpellIid = teamworkOption.affectedId

            assertSoftly {
                teamworkOption.affectorId shouldBe ctoSpellIid
                teamworkOption.grpId shouldBe teamworkAbilityGrpId
                teamworkOption.manaCostList.map { it.objectId }.toSet() shouldBe setOf(ctoSpellIid)
                cto.castingTimeOptionReqList.map { it.castingTimeOptionType } shouldContain CastingTimeOptionType.Done
            }

            val payCostSlice = after { respondToOptionalCost(teamworkOption.ctoId) }
            val payCosts = payCostSlice.expectOnePayCostsReq()
            val payCostMessage = payCostSlice.messages.single { it.hasPayCostsReq() }
            val selection = payCosts.effectCostReq.costSelection
            val weightsById = selection.idsList.zip(selection.weightsList).toMap()
            val promptSourceIid =
                payCostMessage.prompt.parametersList
                    .first { it.parameterName == "CardId" }
                    .numberValue
            val sourceObject = payCostSlice.messages.allGameObjects().last { it.instanceId == promptSourceIid }

            assertSoftly {
                payCostMessage.prompt.promptId shouldBe
                    checkNotNull(TapPaymentDescriptor.grounded(TapPaymentKind.TotalPower, 2)).promptId
                promptSourceIid shouldNotBe ctoSpellIid
                sourceObject.type shouldBe GameObjectType.Card
                sourceObject.zoneId shouldBe ZoneIds.STACK
                payCostSlice.messages
                    .flatMap { message ->
                        if (message.hasGameStateMessage()) message.gameStateMessage.zonesList else emptyList()
                    }.last { it.zoneId == ZoneIds.STACK }
                    .objectInstanceIdsList shouldContain promptSourceIid
                payCosts.hasPaymentActions() shouldBe true
                payCosts.effectCostReq.effectCostType shouldBe EffectCostType.Select_a59c
                selection.minSel shouldBe 2
                selection.maxSel shouldBe Int.MAX_VALUE
                selection.context shouldBe SelectionContext.NonManaPayment
                selection.optionContext shouldBe OptionContext.Payment
                selection.listType shouldBe SelectionListType.Dynamic
                selection.idType shouldBe IdType.InstanceId_ab2c
                selection.validationType shouldBe SelectionValidationType.NonRepeatable
                selection.minWeight shouldBe Int.MIN_VALUE
                selection.maxWeight shouldBe Int.MAX_VALUE
                selection.idsList shouldContain merfolkIid
                selection.idsList shouldContain bearsIid
                selection.idsList shouldContain striderIid
                weightsById[merfolkIid] shouldBe 3
                weightsById[bearsIid] shouldBe 2
                weightsById[striderIid] shouldBe 0
            }

            respondToEffectCost(listOf(merfolkIid))
            passUntilResolved(maxPasses = 10)

            val annotations =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .filter { it.detailInt("type") == CastingTimeOptionType.AdditionalCost.number }
            annotations shouldHaveSize 1

            assertSoftly {
                annotations.single().detailInt("additionalCostGrpId") shouldBe teamworkAbilityGrpId
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Coral Merfolk" }
                    .isTapped shouldBe true
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Timeline Inquiry"
            }
        }
    })
