package forge.nexus.conformance

import forge.game.zone.ZoneType
import org.testng.Assert.assertNull
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

    @Test
    fun revealProducesRevealedCardCreatedAnnotation() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Lightning Bolt", human, ZoneType.Hand)
        }
        val handCard = game.humanPlayer.firstCardIn(ZoneType.Hand)

        val gsm = captureAfterAction(b, game, counter) {
            b.promptBridge.recordReveal(listOf(handCard.id), 1)
        }

        val instanceId = b.getOrAllocInstanceId(handCard.id)
        val revealAnn = checkNotNull(gsm.annotationOrNull(AnnotationType.RevealedCardCreated)) {
            "Should have RevealedCardCreated annotation"
        }
        assertTrue(
            instanceId in revealAnn.affectedIdsList,
            "RevealedCardCreated should reference the revealed card's instanceId ($instanceId)",
        )
    }

    @Test
    fun multiCardRevealProducesMultipleAnnotations() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Lightning Bolt", human, ZoneType.Hand)
            addCard("Giant Growth", human, ZoneType.Hand)
            addCard("Grizzly Bears", human, ZoneType.Hand)
        }
        val handCards = game.humanPlayer.getZone(ZoneType.Hand).cards.toList()

        val gsm = captureAfterAction(b, game, counter) {
            b.promptBridge.recordReveal(handCards.map { it.id }, 1)
        }

        val revealAnns = gsm.annotationsList.filter {
            AnnotationType.RevealedCardCreated in it.typeList
        }
        assertTrue(
            revealAnns.size >= handCards.size,
            "Should have at least ${handCards.size} RevealedCardCreated annotations, got ${revealAnns.size}",
        )
    }

    @Test
    fun noRevealProducesNoAnnotation() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Forest", human, ZoneType.Hand)
        }

        val gsm = captureAfterAction(b, game, counter) { /* no reveal */ }

        assertNull(
            gsm.annotationOrNull(AnnotationType.RevealedCardCreated),
            "Should NOT have RevealedCardCreated when nothing was revealed",
        )
    }
}
