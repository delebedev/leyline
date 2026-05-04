package leyline.conformance

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.game.seedDiffBaseline
import leyline.testkit.BoardTest
import leyline.testkit.gsm
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
    BoardTest({

        test("AI turn produces per-action diffs via EventBus playback") {
            val (b, game, _) = startGameAtMain1()

            val playback = checkNotNull(b.playback) { "GamePlayback should be registered" }

            // Play a land to have mana, then snapshot
            playLand(b) ?: error("playLand failed at seed 42")
            b.seedDiffBaseline(game)

            // Pass through the rest of the human's turn until AI gets priority
            val maxPasses = 30
            @Suppress("UnusedPrivateProperty")
            for (i in 0 until maxPasses) {
                passPriority(b)
                if (playback.hasPendingMessages()) break
            }

            val batches = playback.drainQueue()
            batches.shouldNotBeEmpty()

            // All messages should be GameStateMessage (no ActionsAvailableReq)
            val allMessages = batches.flatten()
            for (msg in allMessages) {
                msg.type shouldBe GREMessageType.GameStateMessage_695e
            }

            // All diffs should use SendHiFi
            val diffs =
                allMessages.filter {
                    it.hasGameStateMessage() && it.gameStateMessage.annotationsCount > 0
                }
            for (diff in diffs) {
                diff.gameStateMessage.update shouldBe GameStateUpdate.SendHiFi
            }
        }

        test("AI action diffs contain ZoneTransfer annotations (local AI visibility)") {
            val (b, game, _) = startGameAtMain1()

            val playback = checkNotNull(b.playback) { "GamePlayback should be registered" }

            playLand(b) ?: error("playLand failed at seed 42")
            b.seedDiffBaseline(game)

            val allBatches = mutableListOf<List<GREToClientMessage>>()
            val maxPasses = 100
            @Suppress("UnusedPrivateProperty")
            for (i in 0 until maxPasses) {
                passPriority(b)
                if (playback.hasPendingMessages()) {
                    val drained = playback.drainQueue()
                    allBatches.addAll(drained)
                    // Snap-vs-snap diffs: break only when we see actual card movements
                    // (objects present in diff), not just phase-transition diffs that
                    // carry zones (Limbo) but no cards.
                    val hasCardMovements =
                        drained
                            .flatten()
                            .filter { it.hasGameStateMessage() }
                            .any { it.gameStateMessage.gameObjectsCount > 0 }
                    if (hasCardMovements) break
                }
            }

            allBatches.shouldNotBeEmpty()

            val allGsms =
                allBatches
                    .flatten()
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }

            val gsmsWithZoneChanges = allGsms.filter { it.zonesCount > 0 }
            gsmsWithZoneChanges.shouldNotBeEmpty()

            val gsmsWithObjects = allGsms.filter { it.gameObjectsCount > 0 }
            gsmsWithObjects.shouldNotBeEmpty()

            for (gsm in gsmsWithObjects) {
                val bfOrStackObjs =
                    gsm.gameObjectsList.filter {
                        it.zoneId == ZoneIds.BATTLEFIELD || it.zoneId == ZoneIds.STACK
                    }
                if (bfOrStackObjs.isEmpty()) continue

                val zoneTransfers =
                    gsm.annotationsList.filter {
                        AnnotationType.ZoneTransfer_af5a in it.typeList
                    }

                gsm.annotationsCount shouldBeGreaterThan 0
                zoneTransfers.shouldNotBeEmpty()
            }
        }
    })
