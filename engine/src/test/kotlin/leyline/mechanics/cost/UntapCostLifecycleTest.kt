package leyline.mechanics.cost

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.TapPaymentDescriptor
import leyline.bridge.handoff.TapPaymentKind
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class UntapCostLifecycleTest :
    SessionTest({
        session(
            "exact untap cost delegates through Forge with stun-aware candidates",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Halo Fountain;Grizzly Bears|Tapped|Counters:STUN=1;Walking Corpse|Tapped;Plains
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            validating = true,
        ) {
            activateAbility("Halo Fountain", abilityIndex = 0).shouldBeTrue()
            val battlefield = human.getZone(ZoneType.Battlefield).cards
            val bear = battlefield.single { it.name == "Grizzly Bears" }
            val corpse = battlefield.single { it.name == "Walking Corpse" }
            assertSoftly {
                bear.getCounters(CounterEnumType.STUN) shouldBe 1
            }

            selectTargets(listOf(human.battlefield.iid("Walking Corpse")))

            assertSoftly {
                bear.isTapped.shouldBeTrue()
                bear.getCounters(CounterEnumType.STUN) shouldBe 1
                corpse.isTapped shouldBe false
                battlefield.single { it.name == "Halo Fountain" }.isTapped.shouldBeTrue()
            }
        }

        session(
            "grounded untap-two payment binds exact candidates to the stack ability",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Halo Fountain;Grizzly Bears|Tapped;Walking Corpse|Tapped;Plains;Plains;Plains
                humanlibrary=Plains;Plains;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            validating = true,
        ) {
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val corpseIid = human.battlefield.iid("Walking Corpse")
            val paymentSlice = after { activateAbility("Halo Fountain", abilityIndex = 1).shouldBeTrue() }
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
                    checkNotNull(TapPaymentDescriptor.grounded(TapPaymentKind.UntapExact, 2)).promptId
                sourceObject.type shouldBe GameObjectType.Ability
                selection.minSel shouldBe 2
                selection.maxSel shouldBe 2
                selection.idsList shouldContain bearIid
                selection.idsList shouldContain corpseIid
                selection.weightsList.toSet() shouldBe setOf(1)
            }

            respondToEffectCost(listOf(bearIid, corpseIid))
            passUntilResolved(maxPasses = 4)

            val battlefield = human.getZone(ZoneType.Battlefield).cards
            assertSoftly {
                battlefield.single { it.name == "Halo Fountain" }.isTapped.shouldBeTrue()
                battlefield.single { it.name == "Grizzly Bears" }.isTapped shouldBe false
                battlefield.single { it.name == "Walking Corpse" }.isTapped shouldBe false
            }
        }
    })
