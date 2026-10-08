package leyline.session.stack

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.game.bundle.SearchWindowMaterializer
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.EmblemSnapshot
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.battlefield
import leyline.testkit.detailInt
import leyline.testkit.detailUint
import leyline.testkit.gameStateMessages
import leyline.testkit.graveyard
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class AbilityIdentityLifecycleTest :
    SessionTest({
        session(
            "cross-card activation agrees with its object and resolves the offered definition",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Grizzly Bears|Id:1;Presence of Gond|AttachedTo:1;Plains;Plains
                humanlibrary=Forest;Forest
                ailibrary=Island;Island
                """.trimIndent(),
        ) {
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val offer =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.Activate_add3 && it.instanceId == bearIid }
            val row =
                allMessages
                    .allGameObjects()
                    .last { it.instanceId == bearIid }
                    .uniqueAbilitiesList
                    .single { it.grpId == offer.abilityGrpId }
            row.id shouldBe offer.uniqueAbilityId
            val start = messageSnapshot()
            submitAction(offer)
            passUntilResolved()
            val stack = messagesSince(start).allGameObjects().first { it.type == GameObjectType.Ability && it.parentId == bearIid }
            stack.grpId shouldBe offer.abilityGrpId
            human.battlefield.card("Elf Warrior Token")
        }

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
            "opponent emblem trigger uses its own ability identity",
            turns = 3,
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
            val emblem = messages.allGameObjects().first { it.type == GameObjectType.Emblem }
            val trigger = messages.allGameObjects().first { it.type == GameObjectType.Ability && it.parentId == emblem.instanceId }
            val resolution = messages.annotationsOfType(AnnotationType.ResolutionStart).single { it.affectorId == trigger.instanceId }
            val creation =
                messages
                    .annotationsOfType(AnnotationType.AbilityInstanceCreated)
                    .single { resolution.affectorId in it.affectedIdsList }
            assertSoftly {
                emblemAbility.abilityGrpId shouldBeGreaterThan 0
                emblem.ownerSeatId shouldBe 2
                emblem.controllerSeatId shouldBe 2
                creation.affectorId shouldBe emblem.instanceId
                creation.detailInt("source_zone") shouldBe ZoneIds.COMMAND
                trigger.objectSourceGrpId shouldBe 2
                trigger.grpId shouldBe emblem.uniqueAbilitiesList.single().grpId
                resolution.detailUint("grpid") shouldBe trigger.grpId
            }
        }

        session(
            "emblem preserves lineage and later targets after its creator leaves",
            puzzleFile = "data/puzzles/emblem-sephiroth-persistent.pzl",
        ) {
            val creatorIid = human.battlefield.iid("Sephiroth, Fabled SOLDIER")
            var targetStart = messageSnapshot()
            castSpellByName("End the Festivities").shouldBeTrue()
            repeat(4) {
                passUntil(20) { messagesSince(targetStart).any { it.hasSelectTargetsReq() } }.shouldBeTrue()
                targetStart = messageSnapshot()
                selectTargets(listOf(2))
            }
            passUntil(20) { ai.life == 2 }.shouldBeTrue()
            val emblem = allMessages.allGameObjects().first { it.type == GameObjectType.Emblem }
            val hiddenId =
                bridge.cardRepository
                    .findByGrpId(emblem.objectSourceGrpId)!!
                    .hiddenAbilityIds
                    .single()
                    .first
            assertSoftly {
                emblem.parentId shouldBe creatorIid
                emblem.uniqueAbilitiesList.single().grpId shouldBe hiddenId
                allMessages
                    .gameStateMessages()
                    .any { gsm ->
                        gsm.zonesList.any {
                            it.zoneId == ZoneIds.COMMAND &&
                                emblem.instanceId in it.objectInstanceIdsList
                        }
                    }.shouldBeTrue()
                allMessages
                    .annotationsOfType(
                        AnnotationType.ZoneTransfer_af5a,
                    ).none { emblem.instanceId in it.affectedIdsList }
                    .shouldBeTrue()
            }
            castSpellByName("Murder").shouldBeTrue()
            selectTargets(listOf(creatorIid))
            passUntil(10) { human.getZone(ZoneType.Graveyard).cards.any { it.name == "Sephiroth, Fabled SOLDIER" } }.shouldBeTrue()
            selectTargets(listOf(2))
            passUntil(10) { ai.life == 1 }.shouldBeTrue()
            human.graveyard.card("Sephiroth, Fabled SOLDIER")
            bridge.projectionStateSnapshot().viewerCursors.keys.forEach { seat ->
                bridge.commitProjection(SearchWindowMaterializer(seat).resetBaseline(bridge.projectionStateSnapshot()))
            }
            EmblemSnapshot.capture(human.getZone(ZoneType.Command).cards.single { it.isEmblem }, bridge) shouldBe
                EmblemSnapshot(emblem.objectSourceGrpId, creatorIid, listOf(hiddenId))
            val laterStart = messageSnapshot()
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(ai.battlefield.iid("Centaur Courser")))
            passUntil(10) { messagesSince(laterStart).any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            val ability =
                messagesSince(laterStart).allGameObjects().first {
                    it.type == GameObjectType.Ability &&
                        it.parentId == emblem.instanceId
                }
            selectTargets(listOf(2))
            passUntil(10) { isGameOver() }.shouldBeTrue()
            val later = messagesSince(laterStart)
            assertSoftly {
                ability.grpId shouldBe hiddenId
                ability.objectSourceGrpId shouldBe 2
                later
                    .annotationsOfType(
                        AnnotationType.AbilityInstanceCreated,
                    ).filter { ability.instanceId in it.affectedIdsList }
                    .map { it.affectorId }
                    .distinct()
                    .single() shouldBe
                    emblem.instanceId
                later
                    .persistentAnnotationsOfType(
                        AnnotationType.TargetSpec,
                    ).single { it.affectorId == ability.instanceId }
                    .affectedIdsList shouldContain
                    2
                later
                    .annotationsOfType(
                        AnnotationType.ResolutionStart,
                    ).single { it.affectorId == ability.instanceId }
                    .detailUint("grpid") shouldBe
                    hiddenId
                later.annotationsOfType(AnnotationType.ResolutionComplete).any { it.affectorId == ability.instanceId }.shouldBeTrue()
                later
                    .annotationsOfType(
                        AnnotationType.AbilityInstanceDeleted,
                    ).single { ability.instanceId in it.affectedIdsList }
                    .affectorId shouldBe
                    emblem.instanceId
                allMessages.gameStateMessages().none { emblem.instanceId in it.diffDeletedInstanceIdsList }.shouldBeTrue()
                allMessages
                    .allGameObjects()
                    .filter { it.instanceId == emblem.instanceId }
                    .all {
                        it.parentId == creatorIid &&
                            it.objectSourceGrpId == emblem.objectSourceGrpId
                    }.shouldBeTrue()
                ai.life shouldBe 0
            }
        }
    })
