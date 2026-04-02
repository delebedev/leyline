package leyline.conformance

import forge.game.Game
import forge.game.event.GameEventSpellResolved
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.ForgeCardId
import leyline.game.GameBridge
import leyline.game.MessageCounter
import leyline.game.mapper.ZoneIds
import leyline.game.snapshotFromGame
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
    SubsystemTest({

        // --- Local helpers ---

        /** Cast a creature to stack: play land for mana, cast creature. */
        fun castCreatureToStack(
            b: GameBridge,
            game: Game,
            counter: MessageCounter,
        ): Pair<forge.game.card.Card, Int> {
            playLand(b) ?: error("playLand failed")
            b.snapshotFromGame(game)

            val creature = humanPlayer(b).getZone(ZoneType.Hand).cards.first { it.isCreature }
            val cardId = creature.id

            castCreature(b) ?: error("castCreature failed")
            b.snapshotFromGame(game, counter.nextGsId())

            val stackCard = game.stackZone.cards.first { it.id == cardId }
            return stackCard to cardId
        }

        // ===================================================================
        // 1. Cast — zone transfer & annotations
        // ===================================================================

        test("CastSpell: zone transfer Hand→Stack") {
            val gsm = castSpellAndCapture() ?: error("No cast at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            zt.detailString("category") shouldBe "CastSpell"
            zt.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
            zt.detailInt("zone_dest") shouldBe ZoneIds.STACK
        }

        test("CastSpell: annotation types — OIC, ZT, mana bracket, UAT") {
            val gsm = castSpellAndCapture() ?: error("No cast at seed 42")
            val types = gsm.annotationsList.map { it.typeList.first() }

            types shouldContain AnnotationType.ObjectIdChanged
            types shouldContain AnnotationType.ZoneTransfer_af5a
            types shouldContain AnnotationType.AbilityInstanceCreated
            types shouldContain AnnotationType.TappedUntappedPermanent
            types shouldContain AnnotationType.ManaPaid
            types shouldContain AnnotationType.AbilityInstanceDeleted
            types shouldContain AnnotationType.UserActionTaken
        }

        test("CastSpell: OIC before ZT, Limbo contains old instanceId") {
            val (b, game, counter) = startGameAtMain1()
            playLand(b)
            b.snapshotFromGame(game)

            val creature = humanPlayer(b).getZone(ZoneType.Hand).cards.first { it.isCreature }
            val origId = b.getOrAllocInstanceId(ForgeCardId(creature.id))
            val cardId = creature.id

            castCreature(b)
            val gsm = postAction(game, b, counter).gsmOrNull ?: error("No GSM after cast")
            val newId = b.getOrAllocInstanceId(ForgeCardId(cardId))

            origId shouldNotBe newId

            // OIC must precede ZT in annotation list
            val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            val oicIdx = gsm.annotationsList.indexOf(oic)
            val ztIdx = gsm.annotationsList.indexOf(zt)
            oicIdx shouldBe (ztIdx - 1)

            assertLimboContains(gsm, origId.value)
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
                gsm.annotation(AnnotationType.ZoneTransfer_af5a)
                    .affectedIdsList shouldContain newId
                gsm.annotation(AnnotationType.ManaPaid)
                    .affectedIdsList shouldContain newId

                val castUat = gsm.annotations(AnnotationType.UserActionTaken)
                    .first { it.detailInt("actionType") == ActionType.Cast.number }
                castUat.affectedIdsList shouldContain newId

                // AIC references the mana ability, not the spell
                (
                    newId in gsm.annotation(AnnotationType.AbilityInstanceCreated)
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
            (aicIdx < tupIdx) shouldBe true
            (tupIdx < mpIdx) shouldBe true
            (mpIdx < aidIdx) shouldBe true
        }

        // TODO: pre-existing issue — TUP may have 0 or 2 entries depending on
        //  mana cost. Original test assumed 1 TUP; shouldHaveSize(1) may fail
        //  for spells costing 2+ mana from separate lands.
        test("CastSpell: TUP has tapped=1 detail") {
            val gsm = castSpellAndCapture() ?: error("No cast at seed 42")

            val tups = gsm.annotations(AnnotationType.TappedUntappedPermanent)
            tups.shouldHaveSize(1)
            tups[0].detailUint("tapped") shouldBe 1
        }

        // ===================================================================
        // 3. Resolve — annotations & instanceId lifecycle
        // ===================================================================

        test("Resolve: exactly ResolutionStart, ResolutionComplete, ZoneTransfer") {
            val gsm = resolveAndCapture() ?: error("No resolve at seed 42")

            gsm.annotationsList.map { it.typeList.first() } shouldBe listOf(
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
                zt.affectorId.toInt() shouldBe SEAT_ID
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
            val (b, game, counter) = startGameAtMain1()
            playLand(b) ?: error("playLand failed")
            b.snapshotFromGame(game)

            val creature = humanPlayer(b).getZone(ZoneType.Hand).cards.first { it.isCreature }
            val cardId = creature.id

            castCreature(b) ?: error("castCreature failed")
            postAction(game, b, counter)

            val stackId = b.getOrAllocInstanceId(ForgeCardId(cardId))
            b.snapshotFromGame(game)

            passPriority(b)
            postAction(game, b, counter)

            val bfId = b.getOrAllocInstanceId(ForgeCardId(cardId))
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
            val (b, game, counter) = startGameAtMain1()
            val (stackCard, cardId) = castCreatureToStack(b, game, counter)

            val gsm = capture(b, game, counter) {
                game.action.moveToGraveyard(stackCard, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(cardId)).value

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer" }
            zt.category shouldBe "Countered"
        }

        test("fizzled SpellResolved produces Countered not Resolve") {
            val (b, game, counter) = startGameAtMain1()
            val (stackCard, cardId) = castCreatureToStack(b, game, counter)

            val gsm = capture(b, game, counter) {
                game.fireEvent(GameEventSpellResolved(stackCard.firstSpellAbility, true))
                game.action.moveToGraveyard(stackCard, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(cardId)).value

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer" }
            zt.category shouldBe "Countered"
        }
    })
