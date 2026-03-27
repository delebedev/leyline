package leyline.game

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.detailInt
import leyline.conformance.detailString
import leyline.conformance.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * AbilityWordActive persistent annotation conformance.
 *
 * Verifies that Threshold creatures on the battlefield produce
 * AbilityWordActive pAnns with correct AbilityWordName, value,
 * and threshold fields.
 */
class AbilityWordPuzzleTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("Threshold creature emits AbilityWordActive with GY card count") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Dreadwing Scavenger", human, ZoneType.Battlefield)
                repeat(5) { base.addCard("Plains", human, ZoneType.Graveyard) }
            }

            val human = game.humanPlayer
            val scavenger = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Dreadwing Scavenger" }
            val iid = b.getOrAllocInstanceId(ForgeCardId(scavenger.id)).value

            val gsm = base.stateOnlyDiff(game, b, counter)

            val awAnns = gsm.persistentAnnotationsList.filter {
                AnnotationType.AbilityWordActive in it.typeList
            }
            awAnns shouldHaveSize 1
            assertSoftly {
                awAnns[0].affectorId shouldBe iid
                awAnns[0].affectedIdsList shouldBe listOf(iid)
                awAnns[0].detailString("AbilityWordName") shouldBe "Threshold"
                awAnns[0].detailInt("value") shouldBe 5
                awAnns[0].detailInt("threshold") shouldBe 7
            }
        }

        test("AbilityWordActive value updates when GY count changes") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Dreadwing Scavenger", human, ZoneType.Battlefield)
                repeat(5) { base.addCard("Plains", human, ZoneType.Graveyard) }
                base.addCard("Island", human, ZoneType.Hand)
            }

            // Initial diff — value=5
            val gsm1 = base.stateOnlyDiff(game, b, counter)
            val aw1 = gsm1.persistentAnnotationsList.first {
                AnnotationType.AbilityWordActive in it.typeList
            }
            aw1.detailInt("value") shouldBe 5

            // Move card from hand to GY (simulate discard)
            val human = game.humanPlayer
            val island = human.getZone(ZoneType.Hand).cards.first { it.name == "Island" }
            game.action.moveToGraveyard(island, null)

            // Capture next diff — value should be 6
            val gsm2 = base.captureAfterAction(b, game, counter) {}
            val aw2 = gsm2.persistentAnnotationsList.first {
                AnnotationType.AbilityWordActive in it.typeList
            }
            aw2.detailInt("value") shouldBe 6
        }

        test("no AbilityWordActive for non-threshold creatures") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                repeat(5) { base.addCard("Plains", human, ZoneType.Graveyard) }
            }

            val gsm = base.stateOnlyDiff(game, b, counter)
            gsm.persistentAnnotationsList.filter {
                AnnotationType.AbilityWordActive in it.typeList
            } shouldHaveSize 0
        }
    })
