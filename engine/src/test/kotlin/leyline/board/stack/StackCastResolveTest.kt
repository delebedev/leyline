package leyline.board.stack

import forge.game.Game
import forge.game.event.GameEventSpellResolved
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
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

/**
 * Integration journeys for the stack/cast/resolve subsystem.
 *
 * Each journey crosses the Forge bridge once. Exact normalization and ordering
 * rules remain covered by the pure annotation pipeline and finalizer suites.
 */
class StackCastResolveTest :
    BoardTest({

        fun castCreatureToStack(
            bridge: GameBridge,
            game: Game,
            counter: MessageCounter,
        ): Pair<forge.game.card.Card, Int> {
            playLand(bridge) ?: error("playLand failed")
            bridge.seedDiffBaseline(game)

            val castAction = castCreature(bridge) ?: error("castCreature failed")
            val cardId = castAction.cardId.value
            bridge.seedDiffBaseline(game, counter.nextGsId())

            return game.stackZone.cards.first { it.id == cardId } to cardId
        }

        test("cast journey projects identity, transfer, payment, and action ordering") {
            val (gsm, origId, newId) = castSpellAndCaptureWithIds() ?: error("No cast at seed 42")
            val types = gsm.annotationsList.map { it.typeList.first() }
            val objectIdChanged = gsm.annotation(AnnotationType.ObjectIdChanged)
            val zoneTransfer = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            val abilityCreated = gsm.annotation(AnnotationType.AbilityInstanceCreated)
            val manaPaid = gsm.annotations(AnnotationType.ManaPaid)
            val tapped = gsm.annotations(AnnotationType.TappedUntappedPermanent)
            val castAction =
                gsm
                    .annotations(AnnotationType.UserActionTaken)
                    .first { it.detailInt("actionType") == ActionType.Cast.number }
            val tappedSources = tapped.flatMap { it.affectedIdsList }

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

                zoneTransfer.detailString("category") shouldBe "CastSpell"
                zoneTransfer.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
                zoneTransfer.detailInt("zone_dest") shouldBe ZoneIds.STACK
                objectIdChanged.detailInt("orig_id") shouldBe origId
                origId shouldNotBe newId
                gsm.annotationsList.indexOf(objectIdChanged) shouldBe
                    gsm.annotationsList.indexOf(zoneTransfer) - 1
                assertLimboContains(gsm, origId)

                zoneTransfer.affectedIdsList shouldContain newId
                manaPaid.forEach { it.affectedIdsList shouldContain newId }
                castAction.affectedIdsList shouldContain newId
                abilityCreated.affectedIdsList shouldNotContain newId
                castAction.affectedIdsCount shouldBeGreaterThan 0

                types.indexOf(AnnotationType.AbilityInstanceCreated) shouldBeLessThan
                    types.indexOf(AnnotationType.TappedUntappedPermanent)
                types.indexOf(AnnotationType.TappedUntappedPermanent) shouldBeLessThan
                    types.indexOf(AnnotationType.ManaPaid)
                types.indexOf(AnnotationType.ManaPaid) shouldBeLessThan
                    types.indexOf(AnnotationType.AbilityInstanceDeleted)

                tapped.forEach { it.detailInt("tapped") shouldBe 1 }
                manaPaid.forEach {
                    tappedSources shouldContain it.affectorId
                    it.detailInt("id") shouldBeGreaterThan 0
                    it.detailInt("color") shouldBeGreaterThan 0
                }
            }
        }

        test("resolve journey keeps identity and projects the resolution frame") {
            val board = startGameAtMain1()
            playLand(board.bridge) ?: error("playLand failed")
            board.bridge.seedDiffBaseline(board.game)

            val castAction = castCreature(board.bridge) ?: error("castCreature failed")
            board.postAction()
            val stackId = board.bridge.getOrAllocInstanceId(castAction.cardId)
            board.bridge.seedDiffBaseline(board.game)

            passPriority(board.bridge)
            val gsm = board.postAction().gsmOrNull ?: error("No GSM after resolve")
            val battlefieldId = board.bridge.getOrAllocInstanceId(castAction.cardId)
            val resolutionStart = gsm.annotation(AnnotationType.ResolutionStart)
            val resolutionComplete = gsm.annotation(AnnotationType.ResolutionComplete)
            val zoneTransfer = gsm.annotation(AnnotationType.ZoneTransfer_af5a)

            assertSoftly {
                gsm.annotationsList.map { it.typeList.first() } shouldBe
                    listOf(
                        AnnotationType.ResolutionStart,
                        AnnotationType.ResolutionComplete,
                        AnnotationType.ZoneTransfer_af5a,
                    )

                zoneTransfer.detailString("category") shouldBe "Resolve"
                zoneTransfer.detailInt("zone_src") shouldBe ZoneIds.STACK
                zoneTransfer.detailInt("zone_dest") shouldBe ZoneIds.BATTLEFIELD
                zoneTransfer.affectorId shouldBe SEAT_ID

                resolutionStart.affectorId shouldBeGreaterThan 0
                resolutionStart.affectedIdsCount shouldBeGreaterThan 0
                resolutionStart.affectorId shouldBe resolutionStart.getAffectedIds(0)
                resolutionStart.detailUint("grpid") shouldBeGreaterThan 0
                resolutionComplete.affectorId shouldBe resolutionStart.affectorId
                resolutionComplete.getAffectedIds(0) shouldBe resolutionStart.getAffectedIds(0)
                resolutionComplete.detailUint("grpid") shouldBe resolutionStart.detailUint("grpid")

                gsm.annotations(AnnotationType.ObjectIdChanged).shouldBeEmpty()
                battlefieldId shouldBe stackId
                zoneTransfer.affectedIdsList shouldContain stackId.value
                gsm
                    .persistentAnnotation(AnnotationType.EnteredZoneThisTurn)
                    .affectorId shouldBe ZoneIds.BATTLEFIELD
            }
        }

        test("fizzled resolution journey projects Countered from Stack to Graveyard") {
            val board = startGameAtMain1()
            val (stackCard, cardId) = castCreatureToStack(board.bridge, board.game, board.counter)

            val gsm =
                board.snapshotDiff {
                    board.game.fireEvent(GameEventSpellResolved(stackCard.firstSpellAbility, true))
                    board.game.action.moveToGraveyard(stackCard, null)
                }
            val newId = board.bridge.getOrAllocInstanceId(ForgeCardId(cardId)).value
            val zoneTransfer = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer" }

            assertSoftly {
                zoneTransfer.category shouldBe "Countered"
                zoneTransfer.zoneSrc shouldBe ZoneIds.STACK
                zoneTransfer.zoneDest shouldBe ZoneIds.P1_GRAVEYARD
            }
        }
    })
