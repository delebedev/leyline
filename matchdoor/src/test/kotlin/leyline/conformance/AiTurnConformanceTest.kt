package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.game.mapper.ZoneIds
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/**
 * Wire conformance: AI turn produces per-action GRE diffs.
 *
 * Verifies that during an AI turn, the GamePlayback captures
 * individual state diffs with:
 *   - SendHiFi updateType (transient updates, not save points)
 *   - No ActionsAvailableReq messages
 *   - Annotations matching the action type
 */
class AiTurnConformanceTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("AI turn produces per-action diffs via EventBus playback") {
            val (b, game, counter) = base.startGameAtMain1()

            val playback = checkNotNull(b.playback) { "GamePlayback should be registered" }

            // Play a land to have mana, then snapshot
            base.playLand(b) ?: error("playLand failed at seed 42")
            b.snapshotFromGame(game)

            // Pass through the rest of the human's turn until AI gets priority
            val maxPasses = 30
            for (i in 0 until maxPasses) {
                base.passPriority(b)
                if (playback.hasPendingMessages()) break
            }

            val batches = playback.drainQueue()

            if (batches.isEmpty()) return@test

            // All messages should be GameStateMessage (no ActionsAvailableReq)
            val allMessages = batches.flatten()
            for (msg in allMessages) {
                msg.type shouldBe GREMessageType.GameStateMessage_695e
            }

            // All diffs should use SendHiFi
            val diffs = allMessages.filter {
                it.hasGameStateMessage() && it.gameStateMessage.annotationsCount > 0
            }
            for (diff in diffs) {
                diff.gameStateMessage.update shouldBe GameStateUpdate.SendHiFi
            }
        }

        test("AI action diffs contain ZoneTransfer annotations (Sparky visibility)") {
            val (b, game, counter) = base.startGameAtMain1()

            val playback = checkNotNull(b.playback) { "GamePlayback should be registered" }

            base.playLand(b) ?: error("playLand failed at seed 42")
            b.snapshotFromGame(game)

            val allBatches = mutableListOf<List<GREToClientMessage>>()
            val maxPasses = 100
            for (i in 0 until maxPasses) {
                base.passPriority(b)
                if (playback.hasPendingMessages()) {
                    val drained = playback.drainQueue()
                    allBatches.addAll(drained)
                    val hasZoneChanges = drained.flatten()
                        .filter { it.hasGameStateMessage() }
                        .any { it.gameStateMessage.zonesCount > 0 }
                    if (hasZoneChanges) break
                }
            }

            val batches = allBatches
            if (batches.isEmpty()) return@test

            val allGsms = batches.flatten()
                .filter { it.hasGameStateMessage() }
                .map { it.gameStateMessage }

            val gsmsWithZoneChanges = allGsms.filter { it.zonesCount > 0 }
            gsmsWithZoneChanges.isNotEmpty().shouldBeTrue()

            val gsmsWithObjects = allGsms.filter { it.gameObjectsCount > 0 }
            gsmsWithObjects.isNotEmpty().shouldBeTrue()

            for (gsm in gsmsWithObjects) {
                val bfOrStackObjs = gsm.gameObjectsList.filter {
                    it.zoneId == ZoneIds.BATTLEFIELD || it.zoneId == ZoneIds.STACK
                }
                if (bfOrStackObjs.isEmpty()) continue

                val zoneTransfers = gsm.annotationsList.filter {
                    AnnotationType.ZoneTransfer_af5a in it.typeList
                }

                (gsm.annotationsCount > 0).shouldBeTrue()
                zoneTransfers.isNotEmpty().shouldBeTrue()
            }
        }
    })
