package leyline.behavior.mechanics.saga

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.allAnnotations
import leyline.testkit.allGameObjects
import leyline.testkit.detail
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

@Suppress("MissingAssertSoftly") // Each state check gates the next game action.
class SagaFinalChapterTest :
    SessionTest({
        session(
            "ordinary final chapter resolves before Saga sacrifice",
            puzzleFile = "data/puzzles/saga-origin-cast.pzl",
            forgeCatalog = false,
        ) {

            castSpellByName("Origin of Spider-Man").shouldBeTrue()
            passUntil(maxPasses = 20) {
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Origin of Spider-Man" }
            }.shouldBeTrue()
            val saga =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Origin of Spider-Man" }
            val sagaIid = bridge.instanceId(saga)
            passUntil(maxPasses = 30) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .any { it.name == "Spider" || it.name == "Spider Token" }
                .shouldBeTrue()
            val spider =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Spider" || it.name == "Spider Token" }
            val spiderIid = bridge.instanceId(spider)
            saga.getCounters(CounterEnumType.LORE) shouldBe 2
            selectTargets(listOf(spiderIid))
            passUntil(maxPasses = 30) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
            saga.getCounters(CounterEnumType.LORE) shouldBe 3
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .any { it.name == "Origin of Spider-Man" }
                .shouldBeTrue()
            allMessages
                .allAnnotations()
                .none {
                    AnnotationType.ZoneTransfer_af5a in it.typeList &&
                        it.detail("category")?.valueStringList?.firstOrNull() == "Sacrifice"
                }.shouldBeTrue()
            selectTargets(listOf(spiderIid))
            passUntil(maxPasses = 15) {
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Origin of Spider-Man" }
            }.shouldBeTrue()

            val departure =
                allMessages
                    .last { message ->
                        message.gameStateMessage.annotationsList.any { it.detail("category")?.valueStringList == listOf("Sacrifice") }
                    }.gameStateMessage
            val finalChapterId =
                departure.annotationsList
                    .single { AnnotationType.AbilityInstanceDeleted in it.typeList }
                    .affectedIdsList
                    .single()
            allMessages
                .allGameObjects()
                .last { it.type == GameObjectType.Ability && it.instanceId == finalChapterId }
                .parentId shouldBe sagaIid

            human
                .getZone(ZoneType.Battlefield)
                .cards
                .any { it.name == "Origin of Spider-Man" } shouldBe false
            playLand("Plains").shouldBeTrue()
        }

        session(
            "countered final chapter still sacrifices Saga",
            puzzleFile = "data/puzzles/saga-history-cast.pzl",
            forgeCatalog = false,
            fullControl = true,
        ) {
            castSpellByName("History of Benalia").shouldBeTrue()
            passUntil(maxPasses = 20) {
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Knight" || it.name == "Knight Token" }
            }.shouldBeTrue()
            val saga =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "History of Benalia" }
            val sagaIid = bridge.instanceId(saga)
            advanceToPhase("MAIN1", turn = 5)
            saga.getCounters(CounterEnumType.LORE) shouldBe 3

            val chapter =
                allMessages
                    .allGameObjects()
                    .last { it.type == GameObjectType.Ability && it.parentId == sagaIid }
            castSpellByName("Stifle").shouldBeTrue()
            passUntil(maxPasses = 10) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
            selectTargets(listOf(chapter.instanceId))
            passUntil(maxPasses = 15) {
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "History of Benalia" }
            }.shouldBeTrue()
            val annotations = allMessages.allAnnotations()
            annotations
                .any { AnnotationType.AbilityInstanceDeleted in it.typeList && chapter.instanceId in it.affectedIdsList }
                .shouldBeTrue()
            annotations
                .any {
                    AnnotationType.ZoneTransfer_af5a in it.typeList &&
                        it.detail("category")?.valueStringList?.firstOrNull() == "Sacrifice"
                }.shouldBeTrue()
            annotations.none { AnnotationType.ResolutionComplete in it.typeList && it.affectorId == chapter.instanceId }.shouldBeTrue()
            playLand("Plains").shouldBeTrue()
        }
        session(
            "two lore counters trigger chapters II and III before final sacrifice",
            puzzleFile = "data/puzzles/saga-history-two-lore.pzl",
            forgeCatalog = false,
            fullControl = true,
        ) {
            val sagaGrpId = TestCardRegistry.repo.findGrpIdByName("History of Benalia")!!
            val chapterGrpIds =
                TestCardRegistry.repo
                    .findByGrpId(sagaGrpId)!!
                    .abilityIds
                    .map { it.first }
            chapterGrpIds.size shouldBe 3
            val laterChapterGrpIds = chapterGrpIds.drop(1)

            castSpellByName("History of Benalia").shouldBeTrue()
            passUntil(maxPasses = 12) {
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .count { it.name == "Knight" || it.name == "Knight Token" } == 1
            }.shouldBeTrue()
            val saga =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "History of Benalia" }
            val sagaIid = bridge.instanceId(saga)
            saga.getCounters(CounterEnumType.LORE) shouldBe 1
            allMessages
                .allGameObjects()
                .none { it.type == GameObjectType.Ability && it.parentId == sagaIid && it.grpId in laterChapterGrpIds }
                .shouldBeTrue()

            val modal = castSpellUntilCastingTimeOptionsReq("Storyweave").getCastingTimeOptionReq(0).modalReq
            modal.modalOptionsCount shouldBe 2
            respondModalChoice(listOf(modal.getModalOptions(1).grpId))
            passUntil(maxPasses = 10) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
            selectTargets(listOf(sagaIid))
            passUntil(maxPasses = 15) {
                saga.getCounters(CounterEnumType.LORE) == 3 &&
                    allMessages
                        .allGameObjects()
                        .count {
                            it.type == GameObjectType.Ability &&
                                it.parentId == sagaIid &&
                                it.grpId in laterChapterGrpIds
                        } >=
                    2
            }.shouldBeTrue()

            val messages = allMessages.gameStateMessages()
            val increment =
                messages.single { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.CounterAdded in it.typeList &&
                            sagaIid in it.affectedIdsList &&
                            it.detail("transaction_amount")?.valueInt32List?.firstOrNull() == 2
                    }
                }
            val chapters =
                messages
                    .flatMap { it.gameObjectsList }
                    .filter { it.type == GameObjectType.Ability && it.parentId == sagaIid && it.grpId in laterChapterGrpIds }
                    .distinctBy { it.instanceId }
            chapters.map { it.grpId }.toSet() shouldBe laterChapterGrpIds.toSet()
            chapters.map { it.instanceId }.distinct().size shouldBe 2
            increment.annotationsList.count { AnnotationType.CounterAdded in it.typeList && sagaIid in it.affectedIdsList } shouldBe 1
            val stackIds =
                accumulator.zones
                    .getValue(ZoneIds.STACK)
                    .objectInstanceIdsList
            chapters.all { it.instanceId in stackIds }.shouldBeTrue()
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .any { it.name == "History of Benalia" }
                .shouldBeTrue()

            passUntil(maxPasses = 20) {
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "History of Benalia" }
            }.shouldBeTrue()
            val annotations = allMessages.allAnnotations()
            for (chapter in chapters) {
                annotations.count { AnnotationType.ResolutionStart in it.typeList && it.affectorId == chapter.instanceId } shouldBe 1
                annotations.count { AnnotationType.ResolutionComplete in it.typeList && it.affectorId == chapter.instanceId } shouldBe 1
                annotations.count {
                    AnnotationType.AbilityInstanceDeleted in it.typeList && chapter.instanceId in it.affectedIdsList
                } shouldBe
                    1
            }
            annotations.count {
                AnnotationType.ZoneTransfer_af5a in it.typeList &&
                    it.detail("category")?.valueStringList?.firstOrNull() == "Sacrifice"
            } shouldBe
                1
            val knights =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Knight" || it.name == "Knight Token" }
            knights.size shouldBe 2
            knights.all { it.netPower == 4 && it.netToughness == 3 }.shouldBeTrue()
            playLand("Plains").shouldBeTrue()
        }
    })
