package forge.nexus.conformance

import forge.game.zone.ZoneType
import forge.nexus.game.BundleBuilder
import forge.nexus.game.snapshotFromGame
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Reveal annotation pipeline: verifies that card reveals captured via
 * InteractivePromptBridge.recordReveal() produce RevealedCardCreated
 * annotations in the GSM.
 *
 * Reveals are transient-only (no persistent annotation). The real Arena
 * server pairs RevealedCardCreated with RevealedCardDeleted when the
 * reveal display ends; deletion is not yet implemented (deferred).
 *
 * Source: rosetta.md documents RevealedCardCreated = type 59,
 * RevealedCardDeleted = type 60.
 */
@Test(groups = ["integration", "conformance"])
class RevealAnnotationTest : ConformanceTestBase() {

    /**
     * Simulates a card reveal by pushing forge card IDs through the prompt
     * bridge's reveal queue, then verifies RevealedCardCreated annotations
     * appear in the GSM.
     */
    @Test
    fun revealProducesRevealedCardCreatedAnnotation() {
        val (b, game, counter) = startGameAtMain1()

        // Pick a card from hand to simulate being revealed
        val player = b.getPlayer(1)!!
        val handCard = player.getZone(ZoneType.Hand).cards.firstOrNull()
            ?: error("No cards in hand to reveal")

        b.snapshotFromGame(game, counter.currentGsId())

        // Simulate what WebPlayerController.reveal() does: push card IDs
        // through the prompt bridge's reveal queue
        b.promptBridge.recordReveal(listOf(handCard.id), 1)

        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

        // Verify transient RevealedCardCreated annotation
        val instanceId = b.getOrAllocInstanceId(handCard.id)
        val revealAnn = gsm.annotationsList.firstOrNull {
            AnnotationType.RevealedCardCreated in it.typeList
        }
        assertNotNull(revealAnn, "Should have RevealedCardCreated annotation")
        assertTrue(
            instanceId in revealAnn!!.affectedIdsList,
            "RevealedCardCreated should reference the revealed card's instanceId ($instanceId)",
        )
    }

    /**
     * Multiple cards revealed in one batch produce one annotation each.
     */
    @Test
    fun multiCardRevealProducesMultipleAnnotations() {
        val (b, game, counter) = startGameAtMain1()

        val player = b.getPlayer(1)!!
        val handCards = player.getZone(ZoneType.Hand).cards.take(3)
        assertTrue(handCards.size >= 2, "Need at least 2 cards to test multi-reveal")

        b.snapshotFromGame(game, counter.currentGsId())

        // Reveal multiple cards at once
        b.promptBridge.recordReveal(handCards.map { it.id }, 1)

        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

        val revealAnns = gsm.annotationsList.filter {
            AnnotationType.RevealedCardCreated in it.typeList
        }
        assertTrue(
            revealAnns.size >= handCards.size,
            "Should have at least ${handCards.size} RevealedCardCreated annotations, got ${revealAnns.size}",
        )
    }

    /**
     * No reveal recorded → no RevealedCardCreated annotation.
     */
    @Test
    fun noRevealProducesNoAnnotation() {
        val (b, game, counter) = startGameAtMain1()

        b.snapshotFromGame(game, counter.currentGsId())

        // Don't record any reveals
        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

        val revealAnn = gsm.annotationsList.firstOrNull {
            AnnotationType.RevealedCardCreated in it.typeList
        }
        assertTrue(
            revealAnn == null,
            "Should NOT have RevealedCardCreated when nothing was revealed",
        )
    }
}
