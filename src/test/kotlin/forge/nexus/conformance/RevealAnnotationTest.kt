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
 *
 * Uses startWithBoard{} — synchronous, no threads (~0.01s per test).
 */
@Test(groups = ["conformance"])
class RevealAnnotationTest : ConformanceTestBase() {

    /**
     * Simulates a card reveal by pushing forge card IDs through the prompt
     * bridge's reveal queue, then verifies RevealedCardCreated annotations
     * appear in the GSM.
     */
    @Test
    fun revealProducesRevealedCardCreatedAnnotation() {
        val (b, game, counter) = startWithBoard { g, human, _ ->
            addCard("Lightning Bolt", human, ZoneType.Hand)
        }
        val human = game.humanPlayer
        val handCard = human.getZone(ZoneType.Hand).cards.first()

        b.snapshotFromGame(game, counter.currentGsId())

        b.promptBridge.recordReveal(listOf(handCard.id), 1)

        val result = BundleBuilder.stateOnlyDiff(
            game,
            b,
            TEST_MATCH_ID,
            SEAT_ID,
            counter,
        )
        val gsm = result.gsmOrNull ?: error("stateOnlyDiff returned no GSM")

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
        val (b, game, counter) = startWithBoard { g, human, _ ->
            addCard("Lightning Bolt", human, ZoneType.Hand)
            addCard("Giant Growth", human, ZoneType.Hand)
            addCard("Grizzly Bears", human, ZoneType.Hand)
        }
        val human = game.humanPlayer
        val handCards = human.getZone(ZoneType.Hand).cards.toList()
        assertTrue(handCards.size >= 2, "Need at least 2 cards to test multi-reveal")

        b.snapshotFromGame(game, counter.currentGsId())

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
        val (b, game, counter) = startWithBoard { g, human, _ ->
            addCard("Forest", human, ZoneType.Hand)
        }

        b.snapshotFromGame(game, counter.currentGsId())

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
