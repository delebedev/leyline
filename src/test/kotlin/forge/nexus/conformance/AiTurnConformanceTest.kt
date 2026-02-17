package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/**
 * Wire conformance: AI turn produces per-action GRE diffs.
 *
 * Verifies that during an AI turn, the NexusGamePlayback captures
 * individual state diffs with:
 *   - SendHiFi updateType (opponent perspective)
 *   - No ActionsAvailableReq messages
 *   - Annotations matching the action type
 */
@Test(groups = ["integration", "conformance"])
class AiTurnConformanceTest : ConformanceTestBase() {

    @Test(description = "AI turn produces per-action diffs via EventBus playback")
    fun aiTurnProducesPerActionDiffs() {
        val (b, game, gsId) = startGameAtMain1()

        // Playback is registered in GameBridge.start()
        val playback = b.playback
        assertNotNull(playback, "NexusGamePlayback should be registered")
        playback!!.seedCounters(1, gsId)

        // Play a land to have mana, then snapshot
        playLand(b) ?: return
        b.snapshotState(game)

        // Pass through the rest of the human's turn until AI gets priority.
        // Each pass advances through Main1 → Combat → Main2 → End → AI turn.
        // AI actions trigger NexusGamePlayback events with Thread.sleep pacing.
        // Eventually the engine blocks on the human's next priority.
        val maxPasses = 30
        for (i in 0 until maxPasses) {
            passPriority(b)
            // Check if playback captured anything (means AI acted)
            if (playback.hasPendingMessages()) break
        }

        // Drain whatever the playback captured during AI's turn
        val batches = playback.drainQueue()

        if (batches.isEmpty()) {
            // AI may not have acted (passed through all phases)
            println("AI turn: no action batches captured (AI may have passed)")
            return
        }

        println("AI turn captured ${batches.size} action batches:")
        for ((i, batch) in batches.withIndex()) {
            for (msg in batch) {
                val gs = if (msg.hasGameStateMessage()) msg.gameStateMessage else null
                println(
                    "  [$i] type=${msg.type} " +
                        "gsType=${gs?.type} update=${gs?.update} " +
                        "annotations=${
                            gs?.annotationsList
                                ?.flatMap { it.typeList }
                                ?.map { it.name }
                        }",
                )
            }
        }

        // All messages should be GameStateMessage (no ActionsAvailableReq)
        val allMessages = batches.flatten()
        for (msg in allMessages) {
            assertEquals(
                msg.type,
                GREMessageType.GameStateMessage_695e,
                "AI action diffs should only contain GameStateMessage, not ActionsAvailableReq",
            )
        }

        // All non-marker diffs should use SendHiFi (opponent perspective)
        val diffs = allMessages.filter {
            it.hasGameStateMessage() && it.gameStateMessage.annotationsCount > 0
        }
        for (diff in diffs) {
            assertEquals(
                diff.gameStateMessage.update,
                GameStateUpdate.SendHiFi,
                "AI action diffs should use SendHiFi (opponent perspective)",
            )
        }
    }

    /**
     * Reproduces the "Sparky invisible" bug: AI action diffs must contain
     * ZoneTransfer annotations so the client animates card movement.
     *
     * Root cause: buildFromGame with viewingSeatId=1 never adds seat 2's
     * hand cards to gameObjects (they're Private). So recordZone() is never
     * called for them, getPreviousZone() returns null on zone transfer,
     * and no annotations are generated. The client sees the object appear
     * but has no animation instructions → invisible/stuck.
     */
    @Test(description = "AI action diffs contain ZoneTransfer annotations (Sparky visibility)")
    fun aiActionDiffsContainZoneTransferAnnotations() {
        val (b, game, gsId) = startGameAtMain1()

        val playback = b.playback
        assertNotNull(playback, "NexusGamePlayback should be registered")
        playback!!.seedCounters(1, gsId)

        // Play a land so AI has something to respond to, then snapshot
        playLand(b) ?: return
        b.snapshotState(game)

        // Pass through human's turn until AI acts
        val maxPasses = 50
        for (i in 0 until maxPasses) {
            passPriority(b)
            if (playback.hasPendingMessages()) break
        }

        val batches = playback.drainQueue()
        if (batches.isEmpty()) {
            println("AI turn: no action batches captured — AI may have passed all phases")
            return
        }

        // Collect all GSMs from the AI's action diffs
        val allGsms = batches.flatten()
            .filter { it.hasGameStateMessage() }
            .map { it.gameStateMessage }

        // At least one GSM should have zone changes (the AI did something)
        val gsmsWithZoneChanges = allGsms.filter { it.zonesCount > 0 }
        assertTrue(
            gsmsWithZoneChanges.isNotEmpty(),
            "At least one AI action diff should have zone changes",
        )

        // The GSMs with zone changes should also have gameObjects for public
        // zones (battlefield/stack). Without this, the client can't render
        // the AI's cards — they're invisible.
        val gsmsWithObjects = allGsms.filter { it.gameObjectsCount > 0 }
        assertTrue(
            gsmsWithObjects.isNotEmpty(),
            "At least one AI action diff should contain gameObjects. " +
                "This fails when opponent hand cards are never zone-tracked " +
                "(viewingSeatId filtering skips recordZone for seat 2's hand, " +
                "so getPreviousZone returns null and no zone transfer is detected).",
        )

        // Every GSM with objects on battlefield/stack must have ZoneTransfer
        for (gsm in gsmsWithObjects) {
            val bfOrStackObjs = gsm.gameObjectsList.filter {
                it.zoneId == 28 || it.zoneId == 27 // Battlefield or Stack
            }
            if (bfOrStackObjs.isEmpty()) continue

            val zoneTransfers = gsm.annotationsList.filter {
                AnnotationType.ZoneTransfer_af5a in it.typeList
            }

            assertTrue(
                gsm.annotationsCount > 0,
                "AI action diff with objects on BF/Stack must have annotations. " +
                    "Objects: ${bfOrStackObjs.map { "iid=${it.instanceId} zone=${it.zoneId}" }}",
            )

            assertTrue(
                zoneTransfers.isNotEmpty(),
                "AI action diff must contain ZoneTransfer annotation for cards " +
                    "moving to BF/Stack. Got: ${
                        gsm.annotationsList.flatMap { it.typeList }.map { it.name }
                    }",
            )
        }
    }
}
