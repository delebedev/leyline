package leyline.board.stack

import forge.game.Game
import forge.game.event.GameEventSpellResolved
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.game.bundle.MessageCounter
import leyline.game.mapping.ZoneIds
import leyline.game.seedDiffBaseline
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import leyline.testkit.annotation
import leyline.testkit.annotations
import leyline.testkit.assertLimboContains
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.detailUint
import leyline.testkit.findZoneTransfer
import leyline.testkit.gsmOrNull
import leyline.testkit.persistentAnnotation
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/** End-to-end agreement tests for cast, resolve, counter, and fizzle frames. */
class StackCastResolveTest :
    BoardTest({

        fun castCreatureToStack(
            b: GameBridge,
            game: Game,
            counter: MessageCounter,
        ): Pair<forge.game.card.Card, Int> {
            playLand(b) ?: error("playLand failed")
            b.playback?.drainQueue()
            b.seedDiffBaseline(game)
            val castAction = castCreature(b) ?: error("castCreature failed")
            b.seedDiffBaseline(game, counter.nextGsId())
            return game.stackZone.cards.first { it.id == castAction.cardId.value } to castAction.cardId.value
        }

        test("cast frame preserves transfer, identity, action, and mana contracts") {
            val (gsm, originalId, newId) = castSpellAndCaptureWithIds() ?: error("No cast at seed 42")
            val types = gsm.annotationsList.map { it.typeList.first() }
            val transfer = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            val action = gsm.annotations(AnnotationType.UserActionTaken).first { it.detailInt("actionType") == ActionType.Cast.number }
            val abilityCreatedIndex = types.indexOf(AnnotationType.AbilityInstanceCreated)
            val tappedIndex = types.indexOf(AnnotationType.TappedUntappedPermanent)
            val manaPaidIndex = types.indexOf(AnnotationType.ManaPaid)
            val abilityDeletedIndex = types.indexOf(AnnotationType.AbilityInstanceDeleted)
            val tappedSources = gsm.annotations(AnnotationType.TappedUntappedPermanent).flatMap { it.affectedIdsList }

            assertSoftly {
                types shouldContainAll
                    listOf(
                        AnnotationType.ObjectIdChanged,
                        AnnotationType.ZoneTransfer_af5a,
                        AnnotationType.AbilityInstanceCreated,
                        AnnotationType.TappedUntappedPermanent,
                        AnnotationType.ManaPaid,
                        AnnotationType.AbilityInstanceDeleted,
                        AnnotationType.UserActionTaken,
                    )
                types.first() shouldBe AnnotationType.ObjectIdChanged
                types.indexOf(AnnotationType.ObjectIdChanged) shouldBe types.indexOf(AnnotationType.ZoneTransfer_af5a) - 1
                transfer.detailString("category") shouldBe "CastSpell"
                transfer.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
                transfer.detailInt("zone_dest") shouldBe ZoneIds.STACK
                originalId shouldNotBe newId
                transfer.affectedIdsList shouldContain newId
                action.affectedIdsList shouldContain newId
                gsm.annotation(AnnotationType.ManaPaid).affectedIdsList shouldContain newId
                (newId in gsm.annotation(AnnotationType.AbilityInstanceCreated).affectedIdsList) shouldBe false
                action.affectedIdsCount shouldBeGreaterThan 0
                abilityCreatedIndex shouldBeLessThan tappedIndex
                tappedIndex shouldBeLessThan manaPaidIndex
                manaPaidIndex shouldBeLessThan abilityDeletedIndex
            }
            assertLimboContains(gsm, originalId)
            gsm.annotations(AnnotationType.TappedUntappedPermanent).forEach { it.detailInt("tapped") shouldBe 1 }
            gsm.annotations(AnnotationType.ManaPaid).forEach { paid ->
                assertSoftly {
                    tappedSources shouldContain paid.affectorId
                    paid.detailInt("id") shouldBeGreaterThan 0
                    paid.detailInt("color") shouldBeGreaterThan 0
                }
            }
        }

        test("resolve frame preserves identity and emits the complete resolution contract") {
            val board = startGameAtMain1()
            playLand(board.bridge) ?: error("playLand failed")
            board.postAction()
            val castAction = castCreature(board.bridge) ?: error("castCreature failed")
            board.postAction()
            val stackId = board.bridge.getOrAllocInstanceId(castAction.cardId)
            board.bridge.seedDiffBaseline(board.game)
            passPriority(board.bridge)
            val gsm = board.postAction().gsmOrNull ?: error("No resolve at seed 42")
            val transfer = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            val start = gsm.annotation(AnnotationType.ResolutionStart)
            val complete = gsm.annotation(AnnotationType.ResolutionComplete)

            assertSoftly {
                gsm.annotationsList.map { it.typeList.first() } shouldBe
                    listOf(AnnotationType.ResolutionStart, AnnotationType.ResolutionComplete, AnnotationType.ZoneTransfer_af5a)
                transfer.detailString("category") shouldBe "Resolve"
                transfer.detailInt("zone_src") shouldBe ZoneIds.STACK
                transfer.detailInt("zone_dest") shouldBe ZoneIds.BATTLEFIELD
                transfer.affectorId shouldBe SEAT_ID
                start.affectorId shouldBeGreaterThan 0
                start.affectedIdsCount shouldBeGreaterThan 0
                start.affectorId shouldBe start.getAffectedIds(0)
                start.detailUint("grpid") shouldBeGreaterThan 0
                complete.affectorId shouldBe start.affectorId
                complete.getAffectedIds(0) shouldBe start.getAffectedIds(0)
                complete.detailUint("grpid") shouldBe start.detailUint("grpid")
                gsm.annotations(AnnotationType.ObjectIdChanged).shouldBeEmpty()
                board.bridge.getOrAllocInstanceId(castAction.cardId) shouldBe stackId
                gsm.persistentAnnotation(AnnotationType.EnteredZoneThisTurn).affectorId shouldBe ZoneIds.BATTLEFIELD
            }
        }

        test("countered creature moves Stack to Graveyard with Countered category") {
            val board = startGameAtMain1()
            val (stackCard, cardId) = castCreatureToStack(board.bridge, board.game, board.counter)
            val gsm = board.snapshotDiff { board.game.action.moveToGraveyard(stackCard, null) }
            val newId = board.bridge.getOrAllocInstanceId(ForgeCardId(cardId)).value
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Countered"
        }

        test("fizzled SpellResolved produces Countered instead of Resolve") {
            val board = startGameAtMain1()
            val (stackCard, cardId) = castCreatureToStack(board.bridge, board.game, board.counter)
            val gsm =
                board.snapshotDiff {
                    board.game.fireEvent(GameEventSpellResolved(stackCard.firstSpellAbility, true))
                    board.game.action.moveToGraveyard(stackCard, null)
                }
            val newId = board.bridge.getOrAllocInstanceId(ForgeCardId(cardId)).value
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Countered"
        }
    })
