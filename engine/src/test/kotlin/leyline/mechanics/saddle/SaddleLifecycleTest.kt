package leyline.mechanics.saddle

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.TapPaymentDescriptor
import leyline.bridge.handoff.TapPaymentKind
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import leyline.testkit.detailUint
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

private val PUZZLE =
    """
    [metadata]
    Name:Saddle Drover Grizzly
    Goal:Saddle Drover Grizzly with a helper creature.
    Turns:10
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Forest
    humanbattlefield=Drover Grizzly;Grizzly Bears
    humanlibrary=Forest;Forest;Forest;Forest
    ailibrary=Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class SaddleLifecycleTest :
    SessionTest({
        test("saddle activation taps helper and emits saddled annotations") {
            startPuzzleRaw(
                PUZZLE.replace("Drover Grizzly;Grizzly Bears", "Drover Grizzly;Grizzly Bears;Coral Merfolk"),
                validating = true,
            )

            val helperIid = human.battlefield.iid("Grizzly Bears")
            val otherHelperIid = human.battlefield.iid("Coral Merfolk")
            val paymentSlice = after { activateAbility("Drover Grizzly").shouldBeTrue() }
            val payment = paymentSlice.expectOnePayCostsReq()
            val paymentMessage = paymentSlice.messages.single { it.hasPayCostsReq() }
            val selection = payment.effectCostReq.costSelection
            val sourceIid =
                paymentMessage.prompt.parametersList
                    .single { it.parameterName == "CardId" }
                    .numberValue
            val sourceObject = paymentSlice.messages.allGameObjects().last { it.instanceId == sourceIid }

            assertSoftly {
                paymentMessage.prompt.promptId shouldBe
                    checkNotNull(TapPaymentDescriptor.grounded(TapPaymentKind.TotalPower, 1)).promptId
                sourceObject.type shouldBe GameObjectType.Ability
                selection.minSel shouldBe 1
                selection.maxSel shouldBe Int.MAX_VALUE
                selection.idsList shouldContain helperIid
                selection.idsList shouldContain otherHelperIid
                selection.idsList.zip(selection.weightsList).toMap()[helperIid] shouldBe 2
            }

            respondToEffectCost(listOf(helperIid))
            passUntilResolved(maxPasses = 4)

            val grizzly = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Drover Grizzly" }
            val helper = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val saddledAnn =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.SaddledThisTurn)
                    .firstOrNull { it.affectorId == human.battlefield.iid(grizzly) }
            val saddledDesignation =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.Designation)
                    .firstOrNull {
                        it.affectorId == human.battlefield.iid(grizzly) &&
                            it.detailInt(DetailKeys.DESIGNATION_TYPE) == AnnotationConstants.DESIGNATION_TYPE_SADDLED
                    }
            val gainSaddled =
                allMessages
                    .annotationsOfType(AnnotationType.GainDesignation)
                    .firstOrNull {
                        it.affectorId == human.battlefield.iid(grizzly) &&
                            it.detailInt(DetailKeys.DESIGNATION_TYPE) == AnnotationConstants.DESIGNATION_TYPE_SADDLED
                    }

            assertSoftly {
                grizzly.isSaddled.shouldBeTrue()
                helper.isTapped.shouldBeTrue()
                saddledDesignation.shouldNotBeNull()
                gainSaddled.shouldNotBeNull()
                val saddled = saddledAnn.shouldNotBeNull()
                saddled.affectedIdsList shouldBe listOf(human.battlefield.iid(helper))
                saddled.affectedIdsList shouldContain human.battlefield.iid(helper)
            }
        }

        test("saddled attack condition grants trample") {
            startPuzzleRaw(PUZZLE, validating = true)

            val helperIid = human.battlefield.iid("Grizzly Bears")
            val paymentSlice = after { activateAbility("Drover Grizzly").shouldBeTrue() }
            paymentSlice.expectOnePayCostsReq()
            val paymentMessage = paymentSlice.messages.single { it.hasPayCostsReq() }
            paymentMessage.prompt.promptId shouldBe
                checkNotNull(TapPaymentDescriptor.grounded(TapPaymentKind.TotalPower, 1)).promptId
            respondToEffectCost(listOf(helperIid))
            passUntilResolved(maxPasses = 4)

            val grizzly = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Drover Grizzly" }
            harness.advanceToCombat(turn = 1)
            declareAttackers(listOf(human.battlefield.iid(grizzly)))
            passUntilResolved(maxPasses = 4)

            val trampleGrant =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.AddAbility_af5a)
                    .firstOrNull { ann ->
                        ann.detailUint("grpid") == 14 &&
                            ann.affectedIdsList.contains(human.battlefield.iid(grizzly))
                    }

            assertSoftly {
                trampleGrant.shouldNotBeNull()
                trampleGrant.detailUint("grpid") shouldBe 14
                trampleGrant.affectedIdsList shouldContain human.battlefield.iid(grizzly)
            }
        }

        test("saddled state expires after turn changes") {
            startPuzzleRaw(PUZZLE, validating = true)

            val helperIid = human.battlefield.iid("Grizzly Bears")
            val paymentSlice = after { activateAbility("Drover Grizzly").shouldBeTrue() }
            paymentSlice.expectOnePayCostsReq()
            respondToEffectCost(listOf(helperIid))
            passUntilResolved(maxPasses = 4)
            passUntilTurn(2, maxPasses = 20)

            val grizzly = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Drover Grizzly" }
            withClue("turn=${turn()} phase=${phase()} stack=${game().stack.map { it.sourceCard.name }}") {
                grizzly.isSaddled shouldBe false
            }
        }
    })
