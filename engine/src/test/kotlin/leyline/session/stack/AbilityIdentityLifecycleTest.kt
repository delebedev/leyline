package leyline.session.stack

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.battlefield
import leyline.testkit.detailInt
import leyline.testkit.detailUint
import leyline.testkit.gameStateMessages
import leyline.testkit.graveyard
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class AbilityIdentityLifecycleTest :
    SessionTest({
        session(
            "Blood token activation retains its source and ability identities",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Voldaren Epicure;Mountain
                humanbattlefield=Mountain;Mountain
                humanlibrary=Swamp;Swamp
                ailibrary=Island;Island
                """.trimIndent(),
        ) {
            castSpellByName("Voldaren Epicure").shouldBeTrue()
            passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Blood Token" }
            }.shouldBeTrue()
            val bloodIid = human.battlefield.iid("Blood Token")
            val offer =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Activate_add3 && it.instanceId == bloodIid }
            val start = messageSnapshot()

            submitAction(offer)
            val discard = lastSelectNReq()
            respondToSelectN(listOf(discard.idsList.single()))
            passUntilResolved()

            val messages = messagesSince(start)
            val action =
                messages
                    .annotationsOfType(AnnotationType.UserActionTaken)
                    .single { it.detailInt("actionType") == ActionType.Activate_add3.number }
            val abilities =
                messages
                    .allGameObjects()
                    .filter { it.type == GameObjectType.Ability && it.parentId == bloodIid }
                    .distinctBy { it.instanceId }
            val resolutions =
                messages
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .filter { annotation -> abilities.any { it.instanceId == annotation.affectorId } }
            assertSoftly {
                offer.abilityGrpId shouldBeGreaterThan 0
                action.detailInt("abilityGrpId") shouldBe offer.abilityGrpId
                abilities.map { it.grpId }.distinct() shouldBe listOf(offer.abilityGrpId)
                abilities.forEach { it.objectSourceGrpId shouldBeGreaterThan 0 }
                resolutions.map { it.detailUint("grpid") }.distinct() shouldBe listOf(offer.abilityGrpId)
            }
        }

        session(
            "multi-trigger card retains the selected trigger identity",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Diabolic Servitude
                humangraveyard=Grizzly Bears
                humanbattlefield=Swamp;Swamp;Swamp;Swamp
                humanlibrary=Swamp;Swamp
                ailibrary=Island;Island
                """.trimIndent(),
        ) {
            val targetIid = human.graveyard.iid("Grizzly Bears")
            val start = messageSnapshot()

            castSpellByName("Diabolic Servitude").shouldBeTrue()
            passUntil(maxPasses = 10) { messagesSince(start).any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            selectTargets(listOf(targetIid))
            passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Grizzly Bears" }
            }.shouldBeTrue()

            val sourceGrpId = bridge.cardRepository.findGrpIdByName("Diabolic Servitude")!!
            val messages = messagesSince(start)
            val abilities =
                messages
                    .gameStateMessages()
                    .flatMap { it.gameObjectsList }
                    .filter { it.type == GameObjectType.Ability && it.objectSourceGrpId == sourceGrpId }
                    .distinctBy { it.instanceId }
            val abilityGrpIds = abilities.map { it.grpId }.distinct()
            val resolutions =
                messages
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .filter { annotation -> abilities.any { it.instanceId == annotation.affectorId } }
            assertSoftly {
                abilities.size shouldBe 1
                abilityGrpIds.single() shouldBeGreaterThan 0
                resolutions.map { it.detailUint("grpid") } shouldContain abilityGrpIds.single()
                human.battlefield.card("Grizzly Bears")
            }
        }

        session(
            "effect-backed trigger retains its spawning ability identity",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Chandra, Awakened Inferno|Counters:LOYALTY=6
                humanlibrary=Mountain;Mountain
                ailibrary=Island;Island
                """.trimIndent(),
        ) {
            val chandraIid = human.battlefield.iid("Chandra, Awakened Inferno")
            val emblemAbility =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .first { it.actionType == ActionType.Activate_add3 && it.instanceId == chandraIid }
            val start = messageSnapshot()
            submitAction(emblemAbility)
            passUntilResolved()

            passUntil(maxPasses = 30) { ai.life == 19 }.shouldBeTrue()

            val messages = messagesSince(start)
            val resolution =
                messages
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .single { it.detailUint("grpid") == emblemAbility.abilityGrpId }
            val creation =
                messages
                    .annotationsOfType(AnnotationType.AbilityInstanceCreated)
                    .single { resolution.affectorId in it.affectedIdsList }
            assertSoftly {
                emblemAbility.abilityGrpId shouldBeGreaterThan 0
                creation.affectorId shouldBeGreaterThan 0
                resolution.detailUint("grpid") shouldBe emblemAbility.abilityGrpId
            }
        }
    })
