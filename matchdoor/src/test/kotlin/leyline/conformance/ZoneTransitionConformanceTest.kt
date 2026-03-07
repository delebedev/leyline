package leyline.conformance

import forge.game.ability.AbilityKey
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

        fun human(b: GameBridge): Player = b.getPlayer(1)!!

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
            val origId = b.getOrAllocInstanceId(land.id)
            val forgeCardId = land.id

            base.playLand(b)
            val gsm = base.postAction(game, b, counter).gsmOrNull ?: error("No GSM after play land")
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer annotation" }
            zt.category shouldBe "PlayLand"

            origId shouldNotBe newId

            val oic = checkNotNull(findObjectIdChanged(gsm, origId)) { "Should have ObjectIdChanged" }
            oic.second shouldBe newId
            assertObjectIdChangedBeforeZoneTransfer(gsm, origId)

            assertLimboContains(gsm, origId)
        }

        // 2. Hand → Stack (CastSpell)
        test("Hand → Stack (CastSpell)").config(tags = setOf(IntegrationTag)) {
            val (b, game, counter) = base.startGameAtMain1()
            base.playLand(b)
            b.snapshotFromGame(game)

            val player = human(b)
            val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
                ?: error("No creature in hand")
            val origId = b.getOrAllocInstanceId(creature.id)
            val forgeCardId = creature.id

            base.castCreature(b)
            val gsm = base.postAction(game, b, counter).gsmOrNull ?: error("No GSM after cast")
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for cast" }
            zt.category shouldBe "CastSpell"

            origId shouldNotBe newId
            val oic = findObjectIdChanged(gsm, origId)
            oic.shouldNotBeNull()
            assertObjectIdChangedBeforeZoneTransfer(gsm, origId)
            assertLimboContains(gsm, origId)
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
            val stackId = b.getOrAllocInstanceId(forgeCardId)

            base.passPriority(b)
            val gsm = base.postAction(game, b, counter).gsmOrNull ?: error("No GSM after resolve")
            val resolvedId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(resolvedId)) { "Should have ZoneTransfer for resolve" }
            zt.category shouldBe "Resolve"

            hasEnteredZoneThisTurn(gsm, resolvedId).shouldBeTrue()
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
            val origId = b.getOrAllocInstanceId(creature.id)
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.destroy(creature, null, false, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for destroy" }
            zt.category shouldBe "Destroy"

            origId shouldNotBe newId
            assertObjectIdChangedBeforeZoneTransfer(gsm, origId)
            assertLimboContains(gsm, origId)
            hasEnteredZoneThisTurn(gsm, newId).shouldBeFalse()
        }

        // 5. Battlefield → Graveyard (Sacrifice)
        test("Battlefield → Graveyard (Sacrifice)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val origId = b.getOrAllocInstanceId(creature.id)
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.fireEvent(forge.game.event.GameEventCardSacrificed(creature))
                game.action.moveToGraveyard(creature, null)
            }
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for sacrifice" }
            zt.category shouldBe "Sacrifice"

            origId shouldNotBe newId
            assertLimboContains(gsm, origId)
        }

        // 6. Battlefield → Exile
        test("Battlefield → Exile") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val origId = b.getOrAllocInstanceId(creature.id)
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.exile(creature, null, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for exile" }
            zt.category shouldBe "Exile"

            origId shouldNotBe newId
            assertLimboContains(gsm, origId)
        }

        // 7. Battlefield → Hand (Bounce)
        test("Battlefield → Hand (Bounce)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val origId = b.getOrAllocInstanceId(creature.id)
            val forgeCardId = creature.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToHand(creature, null)
            }
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for bounce" }
            zt.category shouldBe "Bounce"

            origId shouldNotBe newId
            assertLimboContains(gsm, origId)
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
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for draw" }
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
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for discard" }
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
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for mill" }
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
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for library exile" }
            zt.category shouldBe "Exile"
        }

        // 13. Exile → Battlefield (Put)
        test("Exile → Battlefield") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Exile)
            }
            val player = game.humanPlayer
            val exiled = player.getZone(ZoneType.Exile).cards.first { it.isCreature }

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(exiled, null, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(exiled.id)

            val zt = gsm.findZoneTransfer(newId)
            zt.shouldNotBeNull()

            hasEnteredZoneThisTurn(gsm, newId).shouldBeTrue()
        }

        // 14. Graveyard → Battlefield (Reanimate/Put)
        test("Graveyard → Battlefield") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Graveyard)
            }
            val player = game.humanPlayer
            val inGY = player.getZone(ZoneType.Graveyard).cards.first { it.isCreature }

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(inGY, null, AbilityKey.newMap())
            }
            val newId = b.getOrAllocInstanceId(inGY.id)

            val zt = gsm.findZoneTransfer(newId)
            zt.shouldNotBeNull()
            hasEnteredZoneThisTurn(gsm, newId).shouldBeTrue()
        }

        // 15. Graveyard → Hand (Regrowth)
        test("Graveyard → Hand") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Graveyard)
            }
            val player = game.humanPlayer
            val inGY = player.getZone(ZoneType.Graveyard).cards.first { it.isCreature }

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToHand(inGY, null)
            }
            val newId = b.getOrAllocInstanceId(inGY.id)

            val zt = gsm.findZoneTransfer(newId)
            zt.shouldNotBeNull()
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
            val newId = b.getOrAllocInstanceId(forgeCardId)

            val zt = checkNotNull(gsm.findZoneTransfer(newId)) { "Should have ZoneTransfer for Hand→Exile" }
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

            val counterAnn = checkNotNull(
                gsm.annotationsList.firstOrNull { AnnotationType.CounterAdded in it.typeList },
            ) { "Should have CounterAdded annotation" }
            val counterType = checkNotNull(counterAnn.detailsList.firstOrNull { it.key == "counter_type" }) { "CounterAdded should have counter_type detail" }
            counterType.getValueString(0) shouldBe "+1/+1"
            val txnAmount = counterAnn.detailsList.first { it.key == "transaction_amount" }
            txnAmount.getValueInt32(0) shouldBe 2
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

            val counterAnn = checkNotNull(
                gsm.annotationsList.firstOrNull { AnnotationType.CounterRemoved in it.typeList },
            ) { "Should have CounterRemoved annotation" }
            val txnAmount = counterAnn.detailsList.first { it.key == "transaction_amount" }
            txnAmount.getValueInt32(0) shouldBe 2
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
