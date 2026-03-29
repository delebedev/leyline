package leyline.game

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.detail
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

        test("Morbid pAnn has seatId affectorId and morbid permanents in affectedIds") {
            val (b, game, counter) = base.startWithBoard { _, human, ai ->
                base.addCard("Cackling Prowler", human, ZoneType.Battlefield)
                base.addCard("Grizzly Bears", ai, ZoneType.Battlefield)
            }
            val human = game.humanPlayer
            val prowler = human.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Cackling Prowler" }
            val prowlerIid = b.getOrAllocInstanceId(ForgeCardId(prowler.id)).value

            // Kill AI bear — triggers morbid condition
            val ai = game.registeredPlayers.find { it != human }!!
            val bear = ai.getZone(ZoneType.Battlefield).cards.first()
            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToGraveyard(bear, null)
            }

            val morbidAnns = gsm.persistentAnnotationsList.filter {
                AnnotationType.AbilityWordActive in it.typeList &&
                    it.detailString("AbilityWordName") == "Morbid"
            }
            morbidAnns shouldHaveSize 1
            assertSoftly {
                morbidAnns[0].affectorId shouldBe 1 // P1 seatId
                morbidAnns[0].affectedIdsList shouldContain prowlerIid
                morbidAnns[0].detailString("AbilityWordName") shouldBe "Morbid"
                // Boolean-only: no value or threshold details
                morbidAnns[0].detail("value").shouldBeNull()
                morbidAnns[0].detail("threshold").shouldBeNull()
            }
        }

        test("Morbid pAnn absent when no creature died") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Cackling Prowler", human, ZoneType.Battlefield)
            }

            val gsm = base.stateOnlyDiff(game, b, counter)
            gsm.persistentAnnotationsList.filter {
                AnnotationType.AbilityWordActive in it.typeList &&
                    it.detailString("AbilityWordName") == "Morbid"
            }.shouldBeEmpty()
        }

        test("Two morbid cards produce single pAnn with both iids in affectedIds") {
            val (b, game, counter) = base.startWithBoard { _, human, ai ->
                base.addCard("Cackling Prowler", human, ZoneType.Battlefield)
                base.addCard("Needletooth Pack", human, ZoneType.Battlefield)
                base.addCard("Grizzly Bears", ai, ZoneType.Battlefield)
            }
            val human = game.humanPlayer
            val ai = game.registeredPlayers.find { it != human }!!
            val bear = ai.getZone(ZoneType.Battlefield).cards.first()

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToGraveyard(bear, null)
            }

            val morbidAnns = gsm.persistentAnnotationsList.filter {
                AnnotationType.AbilityWordActive in it.typeList &&
                    it.detailString("AbilityWordName") == "Morbid"
            }
            morbidAnns shouldHaveSize 1 // one pAnn per player, not per card
            morbidAnns[0].affectedIdsCount shouldBe 2 // both morbid permanents listed
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
