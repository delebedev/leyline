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
import leyline.testkit.gsm
import leyline.testkit.gsmOrNull
import leyline.testkit.persistentAnnotation
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Stack/cast/resolve subsystem tests.
 *
 * Covers: Hand→Stack (CastSpell), Stack→Battlefield (Resolve),
 * Stack→Graveyard (Countered), annotation ordering, mana bracket,
 * instanceId lifecycle, persistent annotations.
 */
class StackCastResolveTest :
    BoardTest({

        // --- Local helpers ---

        /** Cast a creature to stack: play land for mana, cast creature. */
        fun castCreatureToStack(
            b: GameBridge,
            game: Game,
            counter: MessageCounter,
        ): Pair<forge.game.card.Card, Int> {
            playLand(b) ?: error("playLand failed")
            b.playback?.drainQueue()
            b.seedDiffBaseline(game)

            val castAction = castCreature(b) ?: error("castCreature failed")
            val cardId = castAction.cardId.value
            b.seedDiffBaseline(game, counter.nextGsId())

            val stackCard = game.stackZone.cards.first { it.id == cardId }
            return stackCard to cardId
        }

        // ===================================================================
        // 1. Cast — zone transfer & annotations
        // ===================================================================

        test("CastSpell: zone transfer Hand→Stack") {
            val gsm = castSpellAndCapture() ?: error("No cast at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            assertSoftly {
                zt.detailString("category") shouldBe "CastSpell"
                zt.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
                zt.detailInt("zone_dest") shouldBe ZoneIds.STACK
            }
        }

        test("CastSpell: annotation types — OIC, ZT, mana bracket, UAT") {
            val gsm = castSpellAndCapture() ?: error("No cast at seed 42")
            val types = gsm.annotationsList.map { it.typeList.first() }

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
            }
        }

        test("CastSpell: OIC before ZT, Limbo contains old instanceId") {
            val board = startGameAtMain1()
            playLand(board.bridge)
            board.bridge.playback?.drainQueue()
            board.bridge.seedDiffBaseline(board.game)

            val castAction = castCreature(board.bridge) ?: error("castCreature failed")
            val gsm = board.postAction().gsmOrNull ?: error("No GSM after cast")
            val newId = board.bridge.getOrAllocInstanceId(castAction.cardId)

            // OIC must precede ZT in annotation list
            val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            val origId = oic.detailInt("orig_id")
            origId shouldNotBe newId.value

            val oicIdx = gsm.annotationsList.indexOf(oic)
            val ztIdx = gsm.annotationsList.indexOf(zt)
            oicIdx shouldBe (ztIdx - 1)

            assertLimboContains(gsm, origId)
        }

        test("CastSpell: UAT actionType=Cast") {
            val gsm = castSpellAndCapture() ?: error("No cast at seed 42")

            val uats = gsm.annotations(AnnotationType.UserActionTaken)
            val castUat = uats.first { it.detailInt("actionType") == ActionType.Cast.number }
            castUat.affectedIdsCount shouldBeGreaterThan 0
        }

        test("CastSpell: spell-referencing annotations use new instanceId") {
            val (gsm, _, newId) = castSpellAndCaptureWithIds() ?: error("No cast at seed 42")

            assertSoftly {
                gsm
                    .annotation(AnnotationType.ZoneTransfer_af5a)
                    .affectedIdsList shouldContain newId
                gsm
                    .annotation(AnnotationType.ManaPaid)
                    .affectedIdsList shouldContain newId

                val castUat =
                    gsm
                        .annotations(AnnotationType.UserActionTaken)
                        .first { it.detailInt("actionType") == ActionType.Cast.number }
                castUat.affectedIdsList shouldContain newId

                // AIC references the mana ability, not the spell
                (
                    newId in
                        gsm
                            .annotation(AnnotationType.AbilityInstanceCreated)
                            .affectedIdsList
                ) shouldBe false
            }
        }

        // ===================================================================
        // 2. Cast — mana bracket
        // ===================================================================

        test("CastSpell: mana bracket ordering — AIC < TUP < ManaPaid < AID") {
            val gsm = castSpellAndCapture() ?: error("No cast at seed 42")
            val types = gsm.annotationsList.map { it.typeList.first() }

            val aicIdx = types.indexOf(AnnotationType.AbilityInstanceCreated)
            val tupIdx = types.indexOf(AnnotationType.TappedUntappedPermanent)
            val mpIdx = types.indexOf(AnnotationType.ManaPaid)
            val aidIdx = types.indexOf(AnnotationType.AbilityInstanceDeleted)

            // Strict ordering (not necessarily consecutive — other annotations may interleave)
            assertSoftly {
                aicIdx shouldBeLessThan tupIdx
                tupIdx shouldBeLessThan mpIdx
                mpIdx shouldBeLessThan aidIdx
            }
        }

        // TUP count depends on how many lands the engine taps for the spell's cost.
        // Seed 42 may yield 0+ TUPs (autotap can batch or skip). Each must have tapped=1.
        test("CastSpell: every TUP has tapped=1 detail") {
            val gsm = castSpellAndCapture() ?: error("No cast at seed 42")

            val tups = gsm.annotations(AnnotationType.TappedUntappedPermanent)
            tups.forEach { it.detailInt("tapped") shouldBe 1 }
        }

        test("CastSpell: ManaPaid source and color are populated") {
            val gsm = castSpellAndCapture() ?: error("No cast at seed 42")
            val tappedSources =
                gsm
                    .annotations(AnnotationType.TappedUntappedPermanent)
                    .flatMap { it.affectedIdsList }

            for (manaPaid in gsm.annotations(AnnotationType.ManaPaid)) {
                assertSoftly {
                    tappedSources shouldContain manaPaid.affectorId
                    manaPaid.detailInt("id") shouldBeGreaterThan 0
                    manaPaid.detailInt("color") shouldBeGreaterThan 0
                }
            }
        }

        // ===================================================================
        // 3. Resolve — annotations & instanceId lifecycle
        // ===================================================================

        test("Resolve: exactly ResolutionStart, ResolutionComplete, ZoneTransfer") {
            val gsm = resolveAndCapture() ?: error("No resolve at seed 42")

            gsm.annotationsList.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ResolutionStart,
                    AnnotationType.ResolutionComplete,
                    AnnotationType.ZoneTransfer_af5a,
                )
        }

        test("Resolve: ZoneTransfer category=Resolve, Stack→Battlefield") {
            val gsm = resolveAndCapture() ?: error("No resolve at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            assertSoftly {
                zt.detailString("category") shouldBe "Resolve"
                zt.detailInt("zone_src") shouldBe ZoneIds.STACK
                zt.detailInt("zone_dest") shouldBe ZoneIds.BATTLEFIELD
                zt.affectorId shouldBe SEAT_ID
            }
        }

        test("Resolve: ResolutionStart/Complete fields match") {
            val gsm = resolveAndCapture() ?: error("No resolve at seed 42")

            val rs = gsm.annotation(AnnotationType.ResolutionStart)
            val rc = gsm.annotation(AnnotationType.ResolutionComplete)

            assertSoftly {
                rs.affectorId shouldBeGreaterThan 0
                rs.affectedIdsCount shouldBeGreaterThan 0
                rs.affectorId shouldBe rs.getAffectedIds(0)
                rs.detailUint("grpid") shouldBeGreaterThan 0

                rc.affectorId shouldBe rs.affectorId
                rc.getAffectedIds(0) shouldBe rs.getAffectedIds(0)
                rc.detailUint("grpid") shouldBe rs.detailUint("grpid")
            }
        }

        test("Resolve: same instanceId, no OIC") {
            val gsm = resolveAndCapture() ?: error("No resolve at seed 42")

            // No OIC = no reallocation
            gsm.annotations(AnnotationType.ObjectIdChanged).shouldBeEmpty()
        }

        test("Resolve: keeps same instanceId across Stack→Battlefield") {
            val board = startGameAtMain1()
            playLand(board.bridge) ?: error("playLand failed")
            board.bridge.playback?.drainQueue()
            board.bridge.seedDiffBaseline(board.game)

            val castAction = castCreature(board.bridge) ?: error("castCreature failed")
            board.postAction()

            val stackId = board.bridge.getOrAllocInstanceId(castAction.cardId)
            board.bridge.seedDiffBaseline(board.game)

            passPriority(board.bridge)
            board.postAction()

            val bfId = board.bridge.getOrAllocInstanceId(castAction.cardId)
            bfId shouldBe stackId
        }

        test("Resolve: EnteredZoneThisTurn persistent annotation") {
            val gsm = resolveAndCapture() ?: error("No resolve at seed 42")

            val entered = gsm.persistentAnnotation(AnnotationType.EnteredZoneThisTurn)
            entered.affectorId shouldBe ZoneIds.BATTLEFIELD
        }

        // ===================================================================
        // 4. Countered
        // ===================================================================

        test("countered creature — Stack→Graveyard with Countered category") {
            val board = startGameAtMain1()
            val (stackCard, cardId) = castCreatureToStack(board.bridge, board.game, board.counter)

            val gsm =
                board.snapshotDiff {
                    board.game.action.moveToGraveyard(stackCard, null)
                }
            val newId = board.bridge.getOrAllocInstanceId(ForgeCardId(cardId)).value

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer" }
            zt.category shouldBe "Countered"
        }

        test("fizzled SpellResolved produces Countered not Resolve") {
            val board = startGameAtMain1()
            val (stackCard, cardId) = castCreatureToStack(board.bridge, board.game, board.counter)

            val gsm =
                board.snapshotDiff {
                    board.game.fireEvent(GameEventSpellResolved(stackCard.firstSpellAbility, true))
                    board.game.action.moveToGraveyard(stackCard, null)
                }
            val newId = board.bridge.getOrAllocInstanceId(ForgeCardId(cardId)).value

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer" }
            zt.category shouldBe "Countered"
        }
    })
