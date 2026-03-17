package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.card.CardView
import forge.game.card.CounterEnumType
import forge.game.player.Player
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.GameBridge
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Tier 1 zone-pair coverage: exercises every zone transition the Arena client
 * expects, at the StateMapper level.
 *
 * Tests 1-3 (PlayLand, CastSpell, Resolve) use [startGameAtMain1] because they
 * test the play/cast action pipeline through the bridge.
 *
 * All other tests use [startWithBoard] — no threads, no bridge, ~0.01s per test.
 * Cards are placed directly into zones; transitions triggered via [forge.game.GameAction].
 * Forge events fire synchronously, annotations build inline.
 */
class ZoneTransitionConformanceTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // -----------------------------------------------------------------------
        // Helpers
        // -----------------------------------------------------------------------

        fun human(b: GameBridge): Player = b.getPlayer(SeatId(1))!!

        fun findObjectIdChanged(gsm: GameStateMessage, origId: Int): Pair<Int, Int>? {
            val ann = gsm.annotationsList.firstOrNull {
                AnnotationType.ObjectIdChanged in it.typeList &&
                    it.detailsList.any { d -> d.key == "orig_id" && d.getValueInt32(0) == origId }
            } ?: return null
            val newId = ann.detailsList.first { it.key == "new_id" }.getValueInt32(0)
            return origId to newId
        }

        fun assertObjectIdChangedBeforeZoneTransfer(gsm: GameStateMessage, origId: Int) {
            val annotations = gsm.annotationsList
            val oicIndex = annotations.indexOfFirst {
                AnnotationType.ObjectIdChanged in it.typeList &&
                    it.detailsList.any { d -> d.key == "orig_id" && d.getValueInt32(0) == origId }
            }
            val ztIndex = annotations.indexOfFirst {
                AnnotationType.ZoneTransfer_af5a in it.typeList
            }
            if (oicIndex >= 0 && ztIndex >= 0) {
                (oicIndex < ztIndex).shouldBeTrue()
            }
        }

        fun hasEnteredZoneThisTurn(gsm: GameStateMessage, instanceId: Int): Boolean =
            gsm.persistentAnnotationsList.any {
                AnnotationType.EnteredZoneThisTurn in it.typeList &&
                    instanceId in it.affectedIdsList
            }

        // =======================================================================
        // Group A: Pipeline tests (need bridge — startGameAtMain1)
        // =======================================================================

        // 1. Hand → Battlefield (PlayLand)
        test("Hand → Battlefield (PlayLand)").config(tags = setOf(IntegrationTag)) {
            val (b, game, counter) = base.startGameAtMain1()
            val player = human(b)
            val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
                ?: error("No land in hand")
            val origId = b.getOrAllocInstanceId(ForgeCardId(land.id))
            val forgeCardId = land.id

            base.playLand(b)
            val gsm = base.postAction(game, b, counter).gsmOrNull ?: error("No GSM after play land")
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer annotation" }
            zt.category shouldBe "PlayLand"

            origId shouldNotBe newId

            val oic = checkNotNull(findObjectIdChanged(gsm, origId.value)) { "Should have ObjectIdChanged" }
            oic.second shouldBe newId.value
            assertObjectIdChangedBeforeZoneTransfer(gsm, origId.value)

            assertLimboContains(gsm, origId.value)
        }

        // 2. Hand → Stack (CastSpell)
        test("Hand → Stack (CastSpell)").config(tags = setOf(IntegrationTag)) {
            val (b, game, counter) = base.startGameAtMain1()
            base.playLand(b)
            b.snapshotFromGame(game)

            val player = human(b)
            val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
                ?: error("No creature in hand")
            val origId = b.getOrAllocInstanceId(ForgeCardId(creature.id))
            val forgeCardId = creature.id

            base.castCreature(b)
            val gsm = base.postAction(game, b, counter).gsmOrNull ?: error("No GSM after cast")
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for cast" }
            zt.category shouldBe "CastSpell"

            origId shouldNotBe newId
            val oic = findObjectIdChanged(gsm, origId.value)
            oic.shouldNotBeNull()
            assertObjectIdChangedBeforeZoneTransfer(gsm, origId.value)
            assertLimboContains(gsm, origId.value)
        }

        // 3. Stack → Battlefield (Resolve)
        test("Stack → Battlefield (Resolve)").config(tags = setOf(IntegrationTag)) {
            val (b, game, counter) = base.startGameAtMain1()
            base.playLand(b)
            b.snapshotFromGame(game)

            val player = human(b)
            val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
                ?: error("No creature in hand")
            val forgeCardId = creature.id

            base.castCreature(b)
            base.postAction(game, b, counter)
            b.snapshotFromGame(game)

            base.passPriority(b)
            val gsm = base.postAction(game, b, counter).gsmOrNull ?: error("No GSM after resolve")
            val resolvedId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(resolvedId.value)) { "Should have ZoneTransfer for resolve" }
            zt.category shouldBe "Resolve"

            hasEnteredZoneThisTurn(gsm, resolvedId.value).shouldBeTrue()
        }

        // =======================================================================
        // Group B: Zone transition tests (no threads — startWithBoard)
        // =======================================================================

        // 4. Battlefield → Graveyard (Destroy)
        test("Battlefield → Graveyard (Destroy)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val origId = b.getOrAllocInstanceId(ForgeCardId(creature.id))
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.destroy(creature, null, false, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for destroy" }
            zt.category shouldBe "Destroy"

            origId shouldNotBe newId
            assertObjectIdChangedBeforeZoneTransfer(gsm, origId.value)
            assertLimboContains(gsm, origId.value)
            hasEnteredZoneThisTurn(gsm, newId.value).shouldBeFalse()
        }

        // 5. Battlefield → Graveyard (Sacrifice)
        test("Battlefield → Graveyard (Sacrifice)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val origId = b.getOrAllocInstanceId(ForgeCardId(creature.id))
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.fireEvent(forge.game.event.GameEventCardSacrificed(CardView.get(creature)))
                game.action.moveToGraveyard(creature, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for sacrifice" }
            zt.category shouldBe "Sacrifice"

            origId shouldNotBe newId
            assertLimboContains(gsm, origId.value)
        }

        // 6. Battlefield → Exile
        test("Battlefield → Exile") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val origId = b.getOrAllocInstanceId(ForgeCardId(creature.id))
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.exile(creature, null, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for exile" }
            zt.category shouldBe "Exile"

            origId shouldNotBe newId
            assertLimboContains(gsm, origId.value)
        }

        // 7. Battlefield → Hand (Bounce)
        test("Battlefield → Hand (Bounce)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val origId = b.getOrAllocInstanceId(ForgeCardId(creature.id))
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToHand(creature, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for bounce" }
            zt.category shouldBe "Bounce"

            origId shouldNotBe newId
            assertLimboContains(gsm, origId.value)
        }

        // 9. Library → Hand (Draw)
        test("Library → Hand (Draw)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Library)
            }
            val player = game.humanPlayer
            val topCard = player.getZone(ZoneType.Library).cards.first()
            val forgeCardId = topCard.id

            val gsm = base.captureAfterAction(b, game, counter) {
                player.drawCard()
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for draw" }
            zt.category shouldBe "Draw"
        }

        // 10. Hand → Graveyard (Discard)
        test("Hand → Graveyard (Discard)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Hand)
            }
            val player = game.humanPlayer
            val cardInHand = player.getZone(ZoneType.Hand).cards.first()
            val forgeCardId = cardInHand.id

            val gsm = base.captureAfterAction(b, game, counter) {
                player.discard(cardInHand, null, false, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for discard" }
            zt.category shouldBe "Discard"
        }

        // 11. Library → Graveyard (Mill)
        test("Library → Graveyard (Mill)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Library)
            }
            val player = game.humanPlayer
            val topCard = player.getZone(ZoneType.Library).cards.first()
            val forgeCardId = topCard.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToGraveyard(topCard, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for mill" }
            zt.category shouldBe "Mill"
        }

        // 12. Library → Exile
        test("Library → Exile") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Library)
            }
            val player = game.humanPlayer
            val topCard = player.getZone(ZoneType.Library).cards.first()
            val forgeCardId = topCard.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.exile(topCard, null, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for library exile" }
            zt.category shouldBe "Exile"
        }

        // 13. Exile → Battlefield (Return)
        test("Exile → Battlefield") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Exile)
            }
            val exiled = game.humanPlayer.getZone(ZoneType.Exile).cards.first { it.isCreature }

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(exiled, null, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(exiled.id))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for Exile→BF" }
            zt.category shouldBe "Return"
            hasEnteredZoneThisTurn(gsm, newId.value).shouldBeTrue()
        }

        // 14. Graveyard → Battlefield (Return)
        test("Graveyard → Battlefield") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Graveyard)
            }
            val inGY = game.humanPlayer.getZone(ZoneType.Graveyard).cards.first { it.isCreature }

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(inGY, null, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(inGY.id))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for GY→BF" }
            zt.category shouldBe "Return"
            hasEnteredZoneThisTurn(gsm, newId.value).shouldBeTrue()
        }

        // 15. Graveyard → Hand (Return)
        test("Graveyard → Hand") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Graveyard)
            }
            val inGY = game.humanPlayer.getZone(ZoneType.Graveyard).cards.first { it.isCreature }

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToHand(inGY, null)
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(inGY.id))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for GY→Hand" }
            zt.category shouldBe "Return"
        }

        // 16. Hand → Exile
        test("Hand → Exile") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Hand)
            }
            val player = game.humanPlayer
            val cardInHand = player.getZone(ZoneType.Hand).cards.first()
            val forgeCardId = cardInHand.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.exile(cardInHand, null, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            val zt = checkNotNull(gsm.findZoneTransfer(newId.value)) { "Should have ZoneTransfer for Hand→Exile" }
            zt.category shouldBe "Exile"
        }

        // =======================================================================
        // Group C: Mechanic annotations (Stage 4 pipeline — startWithBoard)
        // =======================================================================

        // C1. Counter added
        test("counter added produces annotation") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }

            val gsm = base.captureAfterAction(b, game, counter) {
                creature.addCounterInternal(CounterEnumType.P1P1, 2, game.humanPlayer, true, null, AbilityKey.newMap())
            }

            val counterAnn = gsm.annotation(AnnotationType.CounterAdded)
            counterAnn.detailString("counter_type") shouldBe "+1/+1"
            counterAnn.detailInt("transaction_amount") shouldBe 2
        }

        // C2. Counter removed
        test("counter removed produces annotation") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }

            // First add counters
            creature.addCounterInternal(CounterEnumType.P1P1, 3, game.humanPlayer, true, null, AbilityKey.newMap())
            b.snapshotFromGame(game, counter.currentGsId())
            b.drainEvents()

            val gsm = base.captureAfterAction(b, game, counter) {
                creature.subtractCounter(CounterEnumType.P1P1, 2, game.humanPlayer)
            }

            val counterAnn = gsm.annotation(AnnotationType.CounterRemoved)
            counterAnn.detailInt("transaction_amount") shouldBe 2
        }

        // C3. Library shuffle
        test("shuffle event fires but annotation suppressed") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Library)
                base.addCard("Forest", human, ZoneType.Library)
            }
            val player = game.humanPlayer

            val gsm = base.captureAfterAction(b, game, counter) {
                player.shuffle(null)
            }

            val shuffleAnn = gsm.annotationsList.firstOrNull {
                AnnotationType.Shuffle in it.typeList
            }
            shuffleAnn.shouldBeNull()
        }
    })
