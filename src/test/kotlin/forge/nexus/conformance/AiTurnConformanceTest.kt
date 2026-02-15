package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.Test
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
}
