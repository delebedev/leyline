package leyline.mechanics.cost

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.handoff.TapPaymentDescriptor
import leyline.bridge.handoff.TapPaymentKind
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import leyline.testkit.after

class OrdinaryTapCostLifecycleTest :
    SessionTest({
        session(
            "spell tap-cost prompt uses the same-cut stack card identity",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanhand=Fear of Exposure
                humanbattlefield=Forest;Forest;Forest;Forest;Forest;Grizzly Bears;Walking Corpse
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            validating = true,
        ) {
            val handIid = human.hand.iid("Fear of Exposure")
            val paymentSlice = after { castSpellByName("Fear of Exposure").shouldBeTrue() }
            val payCostsMessage = paymentSlice.messages.single { it.hasPayCostsReq() }
            val sourceIid =
                payCostsMessage.prompt.parametersList
                    .single { it.parameterName == "CardId" }
                    .numberValue
            val stackObject = paymentSlice.messages.allGameObjects().last { it.instanceId == sourceIid }

            assertSoftly {
                sourceIid shouldNotBe handIid
                stackObject.type shouldBe GameObjectType.Card
                stackObject.zoneId shouldBe ZoneIds.STACK
                paymentSlice.messages
                    .flatMap { message ->
                        if (message.hasGameStateMessage()) message.gameStateMessage.zonesList else emptyList()
                    }.last { it.zoneId == ZoneIds.STACK }
                    .objectInstanceIdsList shouldContain sourceIid
            }
        }

        session(
            "ordinary exact-count tap cost delegates through the shared Forge visitor",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanbattlefield=Goldfury Strider;Grizzly Bears;Walking Corpse
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            validating = true,
        ) {
            activateAbility("Goldfury Strider").shouldBeTrue()
            passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val corpseIid = human.battlefield.iid("Walking Corpse")
            val striderIid = human.battlefield.iid("Goldfury Strider")
            selectTargets(listOf(bearIid))

            val battlefield = human.getZone(ZoneType.Battlefield).cards
            val payCostsMessage = allMessages.last { it.hasPayCostsReq() }
            val payCosts = payCostsMessage.payCostsReq
            val selection = payCosts.effectCostReq.costSelection
            val sourceIid =
                payCostsMessage.prompt.parametersList
                    .single { it.parameterName == "CardId" }
                    .numberValue
            val sourceObject = allMessages.allGameObjects().last { it.instanceId == sourceIid }

            assertSoftly {
                payCostsMessage.prompt.promptId shouldBe
                    checkNotNull(TapPaymentDescriptor.grounded(TapPaymentKind.TapExact, 2)).promptId
                selection.minSel shouldBe 2
                selection.maxSel shouldBe 2
                selection.idsList shouldContain bearIid
                selection.idsList shouldContain corpseIid
                selection.weightsList.toSet() shouldBe setOf(1)
                sourceObject.type shouldBe GameObjectType.Ability
                sourceIid shouldBe sourceObject.instanceId
                sourceObject.parentId shouldBe striderIid
                allMessages.annotationsOfType(AnnotationType.AbilityInstanceCreated).filter {
                    sourceIid in it.affectedIdsList
                } shouldHaveSize 1
                allMessages.persistentAnnotationsOfType(AnnotationType.TriggeringObject).filter {
                    it.affectorId == sourceIid
                } shouldHaveSize 0
            }

            respondToEffectCost(listOf(bearIid, corpseIid))

            assertSoftly {
                battlefield.single { it.name == "Goldfury Strider" }.isTapped shouldBe false
                battlefield.single { it.name == "Grizzly Bears" }.isTapped.shouldBeTrue()
                battlefield.single { it.name == "Walking Corpse" }.isTapped.shouldBeTrue()
            }

            passUntilResolved(maxPasses = 4)

            assertSoftly {
                allMessages.annotationsOfType(AnnotationType.AbilityInstanceCreated).filter {
                    sourceIid in it.affectedIdsList
                } shouldHaveSize 1
                allMessages.annotationsOfType(AnnotationType.AbilityInstanceDeleted).filter {
                    sourceIid in it.affectedIdsList
                } shouldHaveSize 1
                allMessages
                    .allGameObjects()
                    .filter { it.type == GameObjectType.Ability && it.parentId == striderIid }
                    .map { it.instanceId }
                    .toSet() shouldBe setOf(sourceIid)
            }
        }

        session(
            "cancelling exact-count tap cost deletes the pre-stack ability",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanbattlefield=Goldfury Strider;Grizzly Bears;Walking Corpse
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            validating = true,
        ) {
            activateAbility("Goldfury Strider").shouldBeTrue()
            passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            val payCostsMessage = allMessages.last { it.hasPayCostsReq() }
            val sourceIid =
                payCostsMessage.prompt.parametersList
                    .single { it.parameterName == "CardId" }
                    .numberValue

            cancelAction()

            assertSoftly {
                allMessages.annotationsOfType(AnnotationType.AbilityInstanceCreated).filter {
                    sourceIid in it.affectedIdsList
                } shouldHaveSize 1
                allMessages.annotationsOfType(AnnotationType.AbilityInstanceDeleted).filter {
                    sourceIid in it.affectedIdsList
                } shouldHaveSize 1
                bridge.getLimboInstanceIds().map { it.value } shouldContain sourceIid
                bridge
                    .projectionStateSnapshot()
                    .annotations.abilityLineage
                    .find(sourceIid) shouldBe null
                bridge.cutCoordinator.oneShotPayCosts
                    .current() shouldBe null
            }
        }
    })
