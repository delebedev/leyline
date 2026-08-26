package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.TapPaymentDescriptor
import leyline.bridge.handoff.TapPaymentKind
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.allGameObjects
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

/**
 * Integration test for vehicle crew mechanic.
 *
 * Validates: crew activation, cost payment (tap creature), vehicle becomes creature, combat attack.
 * Brute Suit (4/3 Vigilance, Crew 1) crewed by Centaur Courser (3/3) attacks for lethal.
 *
 * AILife set to 10 so Centaur Courser's auto-pass attacks (3 damage each) don't kill
 * before we get to crew. After crewing, Brute Suit (4/3) finishes the job.
 */
class VehicleCrewPuzzleTest :
    SessionTest({

        session(
            "crew vehicle and attack for lethal",
            puzzle =
                """
                [metadata]
                Name:Crew and Attack
                Goal:Win
                Turns:4
                Difficulty:Easy
                Description:Crew Brute Suit with Centaur Courser, then attack for lethal.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=10

                humanbattlefield=Brute Suit|Centaur Courser
                humanlibrary=Mountain|Mountain|Mountain|Mountain
                aibattlefield=Coral Merfolk
                ailibrary=Mountain|Mountain|Mountain|Mountain
                """.trimIndent(),
        ) {
            assertSoftly {
                // Runtime priority policy should stop at Main1 when crew ability is available
                phase() shouldBe "MAIN1"

                activateAbility("Brute Suit").shouldBeTrue()

                // Pass priority until game over — engine runtime handles combat
                passUntil(maxPasses = 40) { isGameOver() }.shouldBeTrue()

                isGameOver().shouldBeTrue()
                human.hasWon().shouldBeTrue()
            }
        }

        session(
            "crew payment binds weighted helpers to the stack ability",
            puzzle =
                """
                [metadata]
                Name:Crew payment prompt
                Goal:Crew Brute Suit.
                Turns:2
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Brute Suit;Wall of Runes;Grizzly Bears
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val wall = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Wall of Runes" }
            wall.addStaticAbility(
                "Mode\$ TapPowerValue | ValidSA\$ Activated.Crew+Vehicle | " +
                    "ValidCard\$ Card.Self | Value\$ Toughness",
            )
            val wallIid = human.battlefield.iid(wall)
            val bearsIid = human.battlefield.iid("Grizzly Bears")
            val paymentSlice = after { activateAbility("Brute Suit").shouldBeTrue() }
            val payment = paymentSlice.expectOnePayCostsReq()
            val paymentMessage = paymentSlice.messages.single { it.hasPayCostsReq() }
            val selection = payment.effectCostReq.costSelection
            val sourceIid =
                paymentMessage.prompt.parametersList
                    .single { it.parameterName == "CardId" }
                    .numberValue
            val sourceObject = paymentSlice.messages.allGameObjects().last { it.instanceId == sourceIid }
            val weightsById = selection.idsList.zip(selection.weightsList).toMap()

            assertSoftly {
                paymentMessage.prompt.promptId shouldBe
                    checkNotNull(TapPaymentDescriptor.grounded(TapPaymentKind.TotalPower, 1)).promptId
                sourceObject.type shouldBe GameObjectType.Ability
                selection.minSel shouldBe 1
                selection.maxSel shouldBe Int.MAX_VALUE
                selection.idsList shouldContain wallIid
                selection.idsList shouldContain bearsIid
                weightsById[wallIid] shouldBe 4
                weightsById[bearsIid] shouldBe 2
            }

            respondToEffectCost(listOf(wallIid))
            passUntilResolved(maxPasses = 4)

            assertSoftly {
                wall.isTapped.shouldBeTrue()
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Brute Suit" }
                    .isCreature
                    .shouldBeTrue()
            }
        }
    })
