package leyline.behavior.mechanics.saga

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.TestCardRegistry
import leyline.testkit.detail
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

@Suppress("MissingAssertSoftly") // Each state check gates the next game action.
class SagaFinalChapterTest :
    FunSpec({
        tags(IntegrationTag)

        test("ordinary final chapter resolves before Saga sacrifice") {
            val harness = MatchFlowHarness()
            try {
                harness.connectAndKeepPuzzle("data/puzzles/saga-origin-cast.pzl")
                val game = harness.bridge.getGame()!!

                harness.castSpellByName("Origin of Spider-Man").shouldBeTrue()
                harness
                    .passUntil(maxPasses = 20) {
                        game.humanPlayer
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .any { it.name == "Origin of Spider-Man" }
                    }.shouldBeTrue()
                val saga =
                    game.humanPlayer
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .single { it.name == "Origin of Spider-Man" }
                val sagaIid = harness.bridge.instanceId(saga)
                harness.passUntil(maxPasses = 30) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Spider" || it.name == "Spider Token" }
                    .shouldBeTrue()
                val spider =
                    game.humanPlayer
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .single { it.name == "Spider" || it.name == "Spider Token" }
                val spiderIid = harness.bridge.instanceId(spider)
                saga.getCounters(CounterEnumType.LORE) shouldBe 2
                harness.selectTargets(listOf(spiderIid))
                harness.passUntil(maxPasses = 30) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
                saga.getCounters(CounterEnumType.LORE) shouldBe 3
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Origin of Spider-Man" }
                    .shouldBeTrue()
                harness.allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .none {
                        AnnotationType.ZoneTransfer_af5a in it.typeList &&
                            it.detail("category")?.valueStringList?.firstOrNull() == "Sacrifice"
                    }.shouldBeTrue()
                harness.selectTargets(listOf(spiderIid))
                harness
                    .passUntil(maxPasses = 15) {
                        game.humanPlayer
                            .getZone(ZoneType.Graveyard)
                            .cards
                            .any { it.name == "Origin of Spider-Man" }
                    }.shouldBeTrue()

                val departure =
                    harness.allMessages
                        .lastOrNull { message ->
                            message.hasGameStateMessage() &&
                                message.gameStateMessage.annotationsList.any {
                                    AnnotationType.ZoneTransfer_af5a in it.typeList &&
                                        it.detail("category")?.valueStringList?.firstOrNull() == "Sacrifice"
                                }
                        }?.gameStateMessage
                        ?: error(
                            "No sacrifice: " +
                                harness.allMessages
                                    .filter { it.hasGameStateMessage() }
                                    .flatMap { it.gameStateMessage.annotationsList }
                                    .takeLast(
                                        30,
                                    ).map {
                                        it.typeList to
                                            it.detail("category")?.valueStringList
                                    },
                        )
                val annotations = departure.annotationsList
                val finalChapterId = annotations.single { AnnotationType.AbilityInstanceDeleted in it.typeList }.affectedIdsList.single()
                val objects = harness.allMessages.flatMap { it.gameStateMessageOrBuilder.gameObjectsList }
                val finalChapter =
                    objects.lastOrNull { it.type == GameObjectType.Ability && it.instanceId == finalChapterId }
                        ?: error(
                            "No final chapter object $finalChapterId; ability objects=${objects.filter {
                                it.type == GameObjectType.Ability
                            }.map { it.instanceId to it.grpId }}; departure=${annotations.map { it.typeList }}",
                        )
                finalChapter.parentId shouldBe sagaIid
                val resolution =
                    annotations.indexOfFirst {
                        AnnotationType.ResolutionComplete in it.typeList &&
                            it.affectorId == finalChapter.instanceId
                    }
                val retirement =
                    annotations.indexOfFirst {
                        AnnotationType.AbilityInstanceDeleted in it.typeList &&
                            it.affectedIdsList.contains(finalChapter.instanceId)
                    }
                val changed =
                    annotations.indexOfFirst {
                        AnnotationType.ObjectIdChanged in it.typeList &&
                            it.detail("orig_id")?.valueInt32List?.firstOrNull() == sagaIid
                    }
                val transfer =
                    annotations.indexOfFirst {
                        AnnotationType.ZoneTransfer_af5a in it.typeList &&
                            it.detail("category")?.valueStringList?.firstOrNull() == "Sacrifice"
                    }
                resolution shouldBeGreaterThanOrEqual 0
                (resolution < retirement && retirement < changed && changed < transfer).shouldBeTrue()
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Origin of Spider-Man" } shouldBe false
                harness.playLand("Plains").shouldBeTrue()
            } finally {
                harness.shutdown()
            }
        }

        test("countered final chapter still sacrifices Saga") {
            val harness = MatchFlowHarness(fullControl = true)
            try {
                harness.connectAndKeepPuzzle("data/puzzles/saga-history-cast.pzl")
                val game = harness.bridge.getGame()!!
                harness.castSpellByName("History of Benalia").shouldBeTrue()
                harness
                    .passUntil(maxPasses = 20) {
                        game.humanPlayer
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .any { it.name == "Knight" || it.name == "Knight Token" }
                    }.shouldBeTrue()
                val saga =
                    game.humanPlayer
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .single { it.name == "History of Benalia" }
                val sagaIid = harness.bridge.instanceId(saga)
                harness.advanceToPhase("MAIN1", turn = 5)
                saga.getCounters(CounterEnumType.LORE) shouldBe 3

                val chapter =
                    harness.allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .last { it.type == GameObjectType.Ability && it.parentId == sagaIid }
                harness.castSpellByName("Stifle").shouldBeTrue()
                harness.passUntil(maxPasses = 10) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
                harness.selectTargets(listOf(chapter.instanceId))
                harness
                    .passUntil(maxPasses = 15) {
                        game.humanPlayer
                            .getZone(ZoneType.Graveyard)
                            .cards
                            .any { it.name == "History of Benalia" }
                    }.shouldBeTrue()
                val annotations = harness.allMessages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.annotationsList }
                annotations
                    .any { AnnotationType.AbilityInstanceDeleted in it.typeList && chapter.instanceId in it.affectedIdsList }
                    .shouldBeTrue()
                annotations
                    .any {
                        AnnotationType.ZoneTransfer_af5a in it.typeList &&
                            it.detail("category")?.valueStringList?.firstOrNull() == "Sacrifice"
                    }.shouldBeTrue()
                annotations.none { AnnotationType.ResolutionComplete in it.typeList && it.affectorId == chapter.instanceId }.shouldBeTrue()
                harness.playLand("Plains").shouldBeTrue()
            } finally {
                harness.shutdown()
            }
        }
        test("two lore counters trigger chapters II and III before final sacrifice") {
            val harness = MatchFlowHarness(fullControl = true)
            try {
                harness.connectAndKeepPuzzle("data/puzzles/saga-history-two-lore.pzl")
                val game = harness.bridge.getGame()!!
                val sagaGrpId = TestCardRegistry.repo.findGrpIdByName("History of Benalia")!!
                val chapterGrpIds =
                    TestCardRegistry.repo
                        .findByGrpId(sagaGrpId)!!
                        .abilityIds
                        .map { it.first }
                chapterGrpIds.size shouldBe 3

                harness.castSpellByName("History of Benalia").shouldBeTrue()
                harness
                    .passUntil(maxPasses = 12) {
                        game.humanPlayer
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .count { it.name == "Knight" || it.name == "Knight Token" } == 1
                    }.shouldBeTrue()
                val saga =
                    game.humanPlayer
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .single { it.name == "History of Benalia" }
                val sagaIid = harness.bridge.instanceId(saga)
                saga.getCounters(CounterEnumType.LORE) shouldBe 1
                harness.allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .none { it.type == GameObjectType.Ability && it.parentId == sagaIid && it.grpId in chapterGrpIds.drop(1) }
                    .shouldBeTrue()

                val modal = harness.castSpellUntilCastingTimeOptionsReq("Storyweave").getCastingTimeOptionReq(0).modalReq
                modal.modalOptionsCount shouldBe 2
                harness.respondModalChoice(listOf(modal.getModalOptions(1).grpId))
                harness.passUntil(maxPasses = 10) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
                harness.selectTargets(listOf(sagaIid))
                harness
                    .passUntil(maxPasses = 15) {
                        saga.getCounters(CounterEnumType.LORE) == 3 &&
                            allMessages
                                .filter { it.hasGameStateMessage() }
                                .flatMap { it.gameStateMessage.gameObjectsList }
                                .count {
                                    it.type == GameObjectType.Ability &&
                                        it.parentId == sagaIid &&
                                        it.grpId in
                                        chapterGrpIds.drop(
                                            1,
                                        )
                                } >=
                            2
                    }.shouldBeTrue()

                val messages = harness.allMessages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
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
                        .filter { it.type == GameObjectType.Ability && it.parentId == sagaIid && it.grpId in chapterGrpIds.drop(1) }
                        .distinctBy { it.instanceId }
                chapters.map { it.grpId }.toSet() shouldBe chapterGrpIds.drop(1).toSet()
                chapters.map { it.instanceId }.distinct().size shouldBe 2
                increment.annotationsList.count { AnnotationType.CounterAdded in it.typeList && sagaIid in it.affectedIdsList } shouldBe 1
                val stackIds =
                    harness.accumulator.zones
                        .getValue(ZoneIds.STACK)
                        .objectInstanceIdsList
                chapters.all { it.instanceId in stackIds }.shouldBeTrue()
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "History of Benalia" }
                    .shouldBeTrue()

                harness
                    .passUntil(maxPasses = 20) {
                        game.humanPlayer
                            .getZone(ZoneType.Graveyard)
                            .cards
                            .any { it.name == "History of Benalia" }
                    }.shouldBeTrue()
                val annotations = harness.allMessages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.annotationsList }
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
                    game.humanPlayer
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .filter { it.name == "Knight" || it.name == "Knight Token" }
                knights.size shouldBe 2
                knights.all { it.netPower == 4 && it.netToughness == 3 }.shouldBeTrue()
                harness.playLand("Plains").shouldBeTrue()
            } finally {
                harness.shutdown()
            }
        }
    })
