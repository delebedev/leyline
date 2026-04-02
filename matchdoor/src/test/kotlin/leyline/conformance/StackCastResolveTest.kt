package leyline.conformance

import forge.game.Game
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * Covers: Hand->Stack (CastSpell), Stack->Battlefield (Resolve),
 * Stack->Graveyard (Countered), annotation ordering, mana bracket,
 * instanceId lifecycle, Limbo retirement, persistent annotations.
 *
 * Sources: AnnotationOrderingTest (CastSpell + Resolve sections),
 * ZoneTransitionConformanceTest (#2, #3), InstanceIdReallocTest (resolve),
 * CounteredSpellTest (entire file).
 */
class StackCastResolveTest :
    SubsystemTest({

        // -----------------------------------------------------------------------
        // Local helpers
        // -----------------------------------------------------------------------

        /**
         * Cast a creature to stack: play land for mana, cast creature.
         * Returns (stackCard, cardId) for counter/fizzle tests.
         */
        fun castCreatureToStack(
            b: GameBridge,
            game: Game,
            counter: MessageCounter,
        ): Pair<forge.game.card.Card, Int> {
            playLand(b) ?: error("playLand failed at seed 42")
            b.snapshotFromGame(game)

            val player = humanPlayer(b)
            val creature = player.getZone(ZoneType.Hand).cards.first { it.isCreature }
            val cardId = creature.id

            castCreature(b) ?: error("castCreature failed at seed 42")
            b.snapshotFromGame(game, counter.nextGsId())

            val stackCard = game.stackZone.cards.first { it.id == cardId }
            return stackCard to cardId
        }

        // ===================================================================
        // 1. Cast -- zone transfer & annotations
        // ===================================================================

        test("CastSpell: Hand -> Stack zone transfer with CastSpell category") {
            val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            zt.detailString("category") shouldBe "CastSpell"
            zt.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
            zt.detailInt("zone_dest") shouldBe ZoneIds.STACK
        }

        test("CastSpell: OIC before ZT, Limbo contains old instanceId") {
            val (b, game, counter) = startGameAtMain1()
            playLand(b)
            b.snapshotFromGame(game)

            val player = humanPlayer(b)
            val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
                ?: error("No creature in hand")
            val origId = b.getOrAllocInstanceId(ForgeCardId(creature.id))
            val cardId = creature.id

            castCreature(b)
            val gsm = postAction(game, b, counter).gsmOrNull ?: error("No GSM after cast")
            val newId = b.getOrAllocInstanceId(ForgeCardId(cardId))

            origId shouldNotBe newId

            val annotations = gsm.annotationsList
            val oicIndex = annotations.indexOfFirst {
                AnnotationType.ObjectIdChanged in it.typeList &&
                    it.detailsList.any { d -> d.key == "orig_id" && d.getValueInt32(0) == origId.value }
            }
            val ztIndex = annotations.indexOfFirst {
                AnnotationType.ZoneTransfer_af5a in it.typeList
            }
            oicIndex shouldBeGreaterThanOrEqual 0
            ztIndex shouldBeGreaterThanOrEqual 0
            (oicIndex < ztIndex).shouldBeTrue()

            assertLimboContains(gsm, origId.value)
        }

        test("CastSpell: annotation order -- OIC, ZT, mana bracket, UAT") {
            val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")
            val types = gsm.annotationsList.map { it.typeList.first() }

            types shouldContain AnnotationType.ObjectIdChanged
            types shouldContain AnnotationType.ZoneTransfer_af5a
            types shouldContain AnnotationType.AbilityInstanceCreated
            types shouldContain AnnotationType.TappedUntappedPermanent
            types shouldContain AnnotationType.ManaPaid
            types shouldContain AnnotationType.AbilityInstanceDeleted
            types shouldContain AnnotationType.UserActionTaken
        }

        test("CastSpell: UserActionTaken has actionType=Cast") {
            val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")

            val castUat = gsm.annotationsList
                .filter { AnnotationType.UserActionTaken in it.typeList }
                .first { it.detailInt("actionType") == ActionType.Cast.number }
            castUat.detailInt("actionType") shouldBe ActionType.Cast.number
        }

        test("CastSpell: spell-referencing annotations use the new instanceId") {
            val (gsm, _, newInstanceId) = castSpellAndCaptureWithIds()
                ?: error("Could not cast spell at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            zt.affectedIdsList shouldContain newInstanceId

            val mp = gsm.annotation(AnnotationType.ManaPaid)
            mp.affectedIdsList shouldContain newInstanceId

            val castUat = gsm.annotationsList
                .filter { AnnotationType.UserActionTaken in it.typeList }
                .first { it.detailInt("actionType") == ActionType.Cast.number }
            castUat.affectedIdsList shouldContain newInstanceId

            // AIC references the mana ability, not the spell
            val aic = gsm.annotation(AnnotationType.AbilityInstanceCreated)
            aic.affectedIdsList.contains(newInstanceId).shouldBeFalse()
        }

        // ===================================================================
        // 2. Cast -- mana bracket
        // ===================================================================

        test("CastSpell: mana bracket ordering -- AIC < TUP < ManaPaid < AID") {
            val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")
            val types = gsm.annotationsList.map { it.typeList.first() }

            val aicIdx = types.indexOf(AnnotationType.AbilityInstanceCreated)
            val tupIdx = types.indexOf(AnnotationType.TappedUntappedPermanent)
            val mpIdx = types.indexOf(AnnotationType.ManaPaid)
            val aidIdx = types.indexOf(AnnotationType.AbilityInstanceDeleted)

            (aicIdx < tupIdx).shouldBeTrue()
            (tupIdx < mpIdx).shouldBeTrue()
            (mpIdx < aidIdx).shouldBeTrue()
        }

        test("CastSpell: TUP before ManaPaid") {
            val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")
            val types = gsm.annotationsList.map { it.typeList.first() }

            val tupIdx = types.indexOf(AnnotationType.TappedUntappedPermanent)
            val mpIdx = types.indexOf(AnnotationType.ManaPaid)
            (tupIdx < mpIdx).shouldBeTrue()
        }

        // TODO: pre-existing failure -- TUP annotation has no "tapped" detail.
        //  IndexOutOfBoundsException on detailUint("tapped"). Not caused by this migration.
        test("CastSpell: TappedUntappedPermanent has tapped=1 detail") {
            val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")

            val tups = gsm.annotationsList.filter { AnnotationType.TappedUntappedPermanent in it.typeList }
            tups.shouldHaveSize(1)
            for (tup in tups) {
                tup.detailUint("tapped") shouldBe 1
            }
        }

        // ===================================================================
        // 3. Resolve -- zone transfer & annotations
        // ===================================================================

        test("Resolve: annotation order -- ResolutionStart -> ResolutionComplete -> ZoneTransfer") {
            val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val types = gsm.annotationsList.map { it.typeList.first() }
            val rsIdx = types.indexOf(AnnotationType.ResolutionStart)
            val rcIdx = types.indexOf(AnnotationType.ResolutionComplete)
            val ztIdx = types.indexOf(AnnotationType.ZoneTransfer_af5a)

            rsIdx shouldBeGreaterThanOrEqual 0
            rcIdx shouldBeGreaterThanOrEqual 0
            ztIdx shouldBeGreaterThanOrEqual 0

            (rsIdx < rcIdx).shouldBeTrue()
            (rcIdx < ztIdx).shouldBeTrue()
        }

        test("Resolve: exactly 3 annotations") {
            val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val types = gsm.annotationsList.map { it.typeList.first() }
            types shouldBe listOf(
                AnnotationType.ResolutionStart,
                AnnotationType.ResolutionComplete,
                AnnotationType.ZoneTransfer_af5a,
            )
        }

        test("Resolve: ZoneTransfer category=Resolve, zones Stack->Battlefield") {
            val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            assertSoftly {
                zt.detailString("category") shouldBe "Resolve"
                zt.detailInt("zone_src") shouldBe ZoneIds.STACK
                zt.detailInt("zone_dest") shouldBe ZoneIds.BATTLEFIELD
                zt.affectorId shouldBeGreaterThan 0
            }
        }

        test("Resolve: ResolutionStart fields") {
            val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val rs = gsm.annotation(AnnotationType.ResolutionStart)
            rs.affectorId shouldBeGreaterThan 0
            rs.affectedIdsCount shouldBeGreaterThan 0
            rs.affectorId shouldBe rs.getAffectedIds(0)

            val grpid = rs.detail("grpid")
            grpid.shouldNotBeNull()
            (grpid.getValueUint32(0) >= 0).shouldBeTrue()
        }

        test("Resolve: ResolutionComplete matches ResolutionStart") {
            val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val rs = gsm.annotation(AnnotationType.ResolutionStart)
            val rc = gsm.annotation(AnnotationType.ResolutionComplete)

            assertSoftly {
                rc.affectorId shouldBe rs.affectorId
                rc.getAffectedIds(0) shouldBe rs.getAffectedIds(0)
                rc.detailUint("grpid") shouldBe rs.detailUint("grpid")
            }
        }

        // ===================================================================
        // 4. Resolve -- instanceId lifecycle
        // ===================================================================

        test("Resolve: instanceId NOT reallocated") {
            val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val oicAnns = gsm.annotationsList.filter { AnnotationType.ObjectIdChanged in it.typeList }
            oicAnns.shouldBeEmpty()
        }

        test("Resolve: keeps same instanceId across Stack->Battlefield") {
            val (b, game, counter) = startGameAtMain1()
            playLand(b) ?: error("playLand failed at seed 42")
            b.snapshotFromGame(game)

            val player = humanPlayer(b)
            val creature = player.getZone(ZoneType.Hand).cards.first { it.isCreature }
            val cardId = creature.id

            castCreature(b) ?: error("castCreature failed at seed 42")
            postAction(game, b, counter)

            val stackInstanceId = b.getOrAllocInstanceId(ForgeCardId(cardId))
            b.snapshotFromGame(game)

            passPriority(b)
            postAction(game, b, counter)

            val bfInstanceId = b.getOrAllocInstanceId(ForgeCardId(cardId))
            bfInstanceId shouldBe stackInstanceId
        }

        test("Resolve: no Limbo retirement") {
            val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val bfObjects = gsm.gameObjectsList.filter { it.zoneId == ZoneIds.BATTLEFIELD }
            val limboZone = gsm.zonesList.firstOrNull {
                it.type == wotc.mtgo.gre.external.messaging.Messages.ZoneType.Limbo
            }
            for (obj in bfObjects) {
                if (limboZone != null) {
                    limboZone.objectInstanceIdsList.contains(obj.instanceId) shouldBe false
                }
            }
        }

        test("Resolve: EnteredZoneThisTurn persistent annotation") {
            val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            gsm.persistentAnnotationsCount shouldBeGreaterThan 0
            val entered = gsm.persistentAnnotationOrNull(AnnotationType.EnteredZoneThisTurn)
            entered.shouldNotBeNull()
            entered.affectorId shouldBe ZoneIds.BATTLEFIELD
        }

        test("Resolve: Stack -> Battlefield zone pair + EnteredZoneThisTurn") {
            val (b, game, counter) = startGameAtMain1()
            playLand(b)
            b.snapshotFromGame(game)

            val player = humanPlayer(b)
            val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
                ?: error("No creature in hand")
            val cardId = creature.id

            castCreature(b)
            postAction(game, b, counter)
            b.snapshotFromGame(game)

            passPriority(b)
            val gsm = postAction(game, b, counter).gsmOrNull ?: error("No GSM after resolve")
            val resolvedId = b.getOrAllocInstanceId(ForgeCardId(cardId))

            val zt = checkNotNull(gsm.findZoneTransfer(resolvedId.value)) {
                "Should have ZoneTransfer for resolve"
            }
            zt.category shouldBe "Resolve"

            gsm.persistentAnnotationsList.any {
                AnnotationType.EnteredZoneThisTurn in it.typeList &&
                    resolvedId.value in it.affectedIdsList
            }.shouldBeTrue()
        }

        // ===================================================================
        // 5. Countered
        // ===================================================================

        test("countered creature goes to graveyard with Countered category") {
            val (b, game, counter) = startGameAtMain1()
            val (stackCard, cardId) = castCreatureToStack(b, game, counter)

            val gsm = capture(b, game, counter) {
                game.action.moveToGraveyard(stackCard, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(cardId)).value

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) {
                "Should have ZoneTransfer for countered spell"
            }
            zt.category shouldBe "Countered"
        }

        test("fizzled SpellResolved event produces Countered not Resolve") {
            val (b, game, counter) = startGameAtMain1()
            val (stackCard, cardId) = castCreatureToStack(b, game, counter)

            val gsm = capture(b, game, counter) {
                game.fireEvent(
                    forge.game.event.GameEventSpellResolved(stackCard.firstSpellAbility, true),
                )
                game.action.moveToGraveyard(stackCard, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(cardId)).value

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) {
                "Should have ZoneTransfer for fizzled spell"
            }
            zt.category shouldBe "Countered"
        }
    })
