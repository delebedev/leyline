package leyline.behavior.annotations.abilitywordactive

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.testkit.*
import leyline.testkit.BoardTest
import leyline.testkit.detail
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * AbilityWordActive persistent annotation conformance.
 *
 * Verifies that Threshold creatures on the battlefield produce
 * AbilityWordActive pAnns with correct AbilityWordName, value,
 * and threshold fields.
 */
class AbilityWordPuzzleTest :
    BoardTest({

        test("Threshold creature emits AbilityWordActive with GY card count") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Dreadwing Scavenger", human, ZoneType.Battlefield)
                    repeat(5) { addCard("Plains", human, ZoneType.Graveyard) }
                }

            val human = board.game.humanPlayer
            val scavenger =
                human.battlefield.card("Dreadwing Scavenger")
            val iid = board.bridge.getOrAllocInstanceId(ForgeCardId(scavenger.id)).value

            // Seeded baseline state: the pAnn is carried in the initial Full GSM,
            // not re-emitted on subsequent Diffs (protocol spec). Assert on the
            // store directly — this is a computation test, not wire shape.
            val awAnns =
                board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.filter {
                    AnnotationType.AbilityWordActive in it.typeList
                }
            assertSoftly {
                awAnns shouldHaveSize 1
                awAnns[0].affectorId shouldBe iid
                awAnns[0].affectedIdsList shouldBe listOf(iid)
                awAnns[0].detailString("AbilityWordName") shouldBe "Threshold"
                awAnns[0].detailInt("value") shouldBe 5
                awAnns[0].detailInt("threshold") shouldBe 7
            }
        }

        test("AbilityWordActive value updates when GY count changes") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Dreadwing Scavenger", human, ZoneType.Battlefield)
                    repeat(5) { addCard("Plains", human, ZoneType.Graveyard) }
                    addCard("Island", human, ZoneType.Hand)
                }

            // Initial value from the store (baseline) — value=5.
            val aw1 =
                board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.first {
                    AnnotationType.AbilityWordActive in it.typeList
                }
            aw1.detailInt("value") shouldBe 5

            // Move card from hand to GY (simulate discard) and drive the pipeline
            // to recompute pAnns against the new GY count.
            val human = board.game.humanPlayer
            val island = human.hand.card("Island")
            board.game.action.moveToGraveyard(island, null)
            board.snapshotDiff {}

            // Post-action store — value should be 6.
            val aw2 =
                board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.first {
                    AnnotationType.AbilityWordActive in it.typeList
                }
            aw2.detailInt("value") shouldBe 6
        }

        test("Expend annotation projects current mana, threshold, and ability identity") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Roughshod Duo", human, ZoneType.Battlefield)
                }
            val human = board.game.humanPlayer
            val duo = human.battlefield.card("Roughshod Duo")
            val duoIid = board.bridge.getOrAllocInstanceId(ForgeCardId(duo.id)).value

            human.expentThisTurn = 4
            board.snapshotDiff {}

            val expend =
                board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.single {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString("AbilityWordName") == "ExpendedMana"
                }
            assertSoftly {
                expend.affectorId shouldBe duoIid
                expend.affectedIdsList shouldBe listOf(duoIid)
                expend.detailInt("value") shouldBe 4
                expend.detailInt("threshold") shouldBe 4
                expend.detailInt("AbilityGrpId") shouldBe 174034
            }
        }

        test("multi-threshold Expend source retains one persistent row per trigger ability") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Muerra, Trash Tactician", human, ZoneType.Battlefield)
                }
            val human = board.game.humanPlayer

            fun expendRows() =
                board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.filter {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString("AbilityWordName") == "ExpendedMana"
                }

            assertSoftly {
                expendRows() shouldHaveSize 2
                expendRows().map { it.detailInt("threshold") }.toSet() shouldBe setOf(4, 8)
                expendRows().map { it.detailInt("AbilityGrpId") }.toSet() shouldBe setOf(174143, 174144)
                expendRows().map { it.detailInt("value") }.toSet() shouldBe setOf(0)
            }

            human.expentThisTurn = 4
            board.snapshotDiff {}
            assertSoftly {
                expendRows() shouldHaveSize 2
                expendRows().map { it.detailInt("threshold") }.toSet() shouldBe setOf(4, 8)
                expendRows().map { it.detailInt("value") }.toSet() shouldBe setOf(4)
            }
        }

        test("Devotion persistent row updates across the five-symbol threshold") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Heliod, Sun-Crowned", human, ZoneType.Battlefield)
                    repeat(3) { addCard("Savannah Lions", human, ZoneType.Battlefield) }
                    addCard("Savannah Lions", human, ZoneType.Hand)
                }
            val human = board.game.humanPlayer

            fun devotionRow() =
                board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.single {
                    AnnotationType.AbilityWordActive in it.typeList && it.detailString("AbilityWordName") == "Devotion"
                }

            assertSoftly {
                devotionRow().detailInt("value") shouldBe 4
                devotionRow().detailInt("threshold") shouldBe 5
                devotionRow().detailInt("AbilityGrpId") shouldBe 100654
            }

            val lion = human.hand.card("Savannah Lions")
            board.snapshotDiff {
                board.game.action.moveToPlay(
                    lion,
                    null,
                    forge.game.ability.AbilityKey
                        .newMap(),
                )
            }
            devotionRow().detailInt("value") shouldBe 5
        }

        test("Descend persistent row updates with permanent cards in graveyard") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("The Everflowing Well", human, ZoneType.Battlefield)
                    repeat(7) { addCard("Plains", human, ZoneType.Graveyard) }
                    addCard("Plains", human, ZoneType.Hand)
                }
            val human = board.game.humanPlayer

            fun descendRow() =
                board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.single {
                    AnnotationType.AbilityWordActive in it.typeList && it.detailString("AbilityWordName") == "Descend"
                }

            assertSoftly {
                descendRow().detailInt("value") shouldBe 7
                descendRow().detailInt("threshold") shouldBe 8
                descendRow().detailInt("AbilityGrpId") shouldBe 169501
            }

            val plains = human.hand.card("Plains")
            board.snapshotDiff { board.game.action.moveToGraveyard(plains, null) }
            descendRow().detailInt("value") shouldBe 8
        }

        test("spell-count persistent row crosses two and resets") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Reverberating Summons", human, ZoneType.Battlefield)
                }
            val human = board.game.humanPlayer
            val source = human.battlefield.card("Reverberating Summons")
            val castAbility = source.firstSpellAbility.also { it.activatingPlayer = human }

            fun spellCountRow() =
                board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.single {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString("AbilityWordName") == "NumberOfSpellsCast"
                }

            assertSoftly {
                spellCountRow().detailInt("value") shouldBe 0
                spellCountRow().detailInt("threshold") shouldBe 2
                spellCountRow().detailInt("AbilityGrpId") shouldBe 188817
            }

            board.snapshotDiff {
                board.game.stack.spellsCastThisTurn
                    .addAll(listOf(castAbility, castAbility))
            }
            spellCountRow().detailInt("value") shouldBe 2
            board.snapshotDiff {
                board.game.stack.spellsCastThisTurn
                    .clear()
            }
            spellCountRow().detailInt("value") shouldBe 0
        }

        test("Morbid pAnn has seatId affectorId and morbid permanents in affectedIds") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Cackling Prowler", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                }
            val human = board.game.humanPlayer
            val prowler =
                human.battlefield.card("Cackling Prowler")
            val prowlerIid = board.bridge.getOrAllocInstanceId(ForgeCardId(prowler.id)).value

            // Kill AI bear — triggers morbid condition
            val ai = board.game.registeredPlayers.find { it != human }!!
            val bear = ai.getZone(ZoneType.Battlefield).cards.first()
            val gsm =
                board.snapshotDiff {
                    board.game.action.moveToGraveyard(bear, null)
                }

            val morbidAnns =
                gsm.persistentAnnotationsList.filter {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString("AbilityWordName") == "Morbid"
                }
            assertSoftly {
                morbidAnns shouldHaveSize 1
                morbidAnns[0].affectorId shouldBe 1 // P1 seatId
                morbidAnns[0].affectedIdsList shouldContain prowlerIid
                morbidAnns[0].detailString("AbilityWordName") shouldBe "Morbid"
                // Boolean-only: no value or threshold details
                morbidAnns[0].detail("value").shouldBeNull()
                morbidAnns[0].detail("threshold").shouldBeNull()
            }
        }

        test("Morbid pAnn absent when no creature died") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Cackling Prowler", human, ZoneType.Battlefield)
                }

            val gsm = board.stateOnlyDiff()
            gsm.persistentAnnotationsList
                .filter {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString("AbilityWordName") == "Morbid"
                }.shouldBeEmpty()
        }

        test("Two morbid cards produce single pAnn with both iids in affectedIds") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Cackling Prowler", human, ZoneType.Battlefield)
                    addCard("Needletooth Pack", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                }
            val human = board.game.humanPlayer
            val ai = board.game.registeredPlayers.find { it != human }!!
            val bear = ai.getZone(ZoneType.Battlefield).cards.first()

            val gsm =
                board.snapshotDiff {
                    board.game.action.moveToGraveyard(bear, null)
                }

            val morbidAnns =
                gsm.persistentAnnotationsList.filter {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString("AbilityWordName") == "Morbid"
                }
            morbidAnns shouldHaveSize 1 // one pAnn per player, not per card
            morbidAnns[0].affectedIdsCount shouldBe 2 // both morbid permanents listed
        }

        test("no AbilityWordActive for non-threshold creatures") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    repeat(5) { addCard("Plains", human, ZoneType.Graveyard) }
                }

            val gsm = board.stateOnlyDiff()
            gsm.persistentAnnotationsList.filter {
                AnnotationType.AbilityWordActive in it.typeList
            } shouldHaveSize 0
        }
    })
