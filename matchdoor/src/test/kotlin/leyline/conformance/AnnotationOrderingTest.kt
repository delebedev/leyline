package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Cross-cutting annotation ordering tests (not subsystem-specific).
 *
 * CastSpell + Resolve ordering tests moved to StackCastResolveTest.
 * PlayLand ordering tests moved to LandManaTest.
 */
class AnnotationOrderingTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        fun assertAnnotationIdsSequential(gsm: GameStateMessage) {
            val ids = gsm.annotationsList.map { it.id }
            ids.shouldNotBeEmpty()
            ids shouldBe ids.sorted()
            ids.toSet().size shouldBe ids.size
        }

        // PlayLand ordering tests moved to LandManaTest
        // CastSpell + Resolve ordering tests moved to StackCastResolveTest

        // ===== Cross-category: annotation IDs =====

        test("annotation IDs sequential across categories") {
            val gsm = base.playLandAndCapture() ?: error("No land in hand at seed 42")
            assertAnnotationIdsSequential(gsm)

            // CastSpell: check each GSM in the triplet independently
            val castBundle = base.castSpellBundle()
            if (castBundle != null) {
                for (msg in castBundle.messages.filter { it.hasGameStateMessage() }) {
                    val gsmInner = msg.gameStateMessage
                    if (gsmInner.annotationsCount > 0) assertAnnotationIdsSequential(gsmInner)
                }
            }

            val resolveGsm = base.resolveAndCapture()
            if (resolveGsm != null) assertAnnotationIdsSequential(resolveGsm)
        }

        // Resolve: EnteredZoneThisTurn persistent annotation moved to StackCastResolveTest
    })
