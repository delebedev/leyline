package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.PlayerAction
import leyline.game.awaitFreshPending
import leyline.game.mapper.ZoneIds
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Tests for instanceId reallocation on zone transfer and Limbo zone accumulation.
 *
 * Real client server allocates a new instanceId every time a card changes zones
 * (except Stack->Battlefield on resolve). The old instanceId is retired to Limbo
 * via objectInstanceIds in the Limbo ZoneInfo — no GameObjectInfo is emitted for it.
 *
 * Limbo is monotonically growing -- retired IDs are never removed. Each subsequent
 * buildFromGame must include the full retirement history.
 */
class InstanceIdReallocTest :
    FunSpec({
        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // ===== PlayLand realloc =====

        test("PlayLand reallocs instanceId") {
            val (b, game, counter) = base.startGameAtMain1()

            val player = b.getPlayer(1) ?: error("Player 1 not found")
            val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
            val origInstanceId = b.getOrAllocInstanceId(land.id)
            val forgeCardId = land.id

            base.playLand(b) ?: error("playLand failed at seed 42")
            base.postAction(game, b, counter)

            val newInstanceId = b.getOrAllocInstanceId(forgeCardId)
            origInstanceId shouldNotBe newInstanceId
        }

        test("PlayLand retires to Limbo") {
            val (b, game, counter) = base.startGameAtMain1()

            val player = b.getPlayer(1) ?: error("Player 1 not found")
            val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
            val origInstanceId = b.getOrAllocInstanceId(land.id)

            base.playLand(b) ?: error("playLand failed at seed 42")
            base.postAction(game, b, counter)

            b.getLimboInstanceIds().shouldContain(origInstanceId)
        }

        test("PlayLand Limbo zone tracking") {
            val (b, game, counter) = base.startGameAtMain1()

            val player = b.getPlayer(1) ?: error("Player 1 not found")
            val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
            val origInstanceId = b.getOrAllocInstanceId(land.id)

            base.playLand(b) ?: error("playLand failed at seed 42")
            val gsm = base.postAction(game, b, counter).gsm

            // Limbo zone should contain the retired instanceId
            assertLimboContains(gsm, origInstanceId)

            // Real server doesn't send GameObjectInfo for Limbo objects
            val limboObjects = gsm.gameObjectsList.filter { it.zoneId == ZoneIds.LIMBO }
            limboObjects.shouldBeEmpty()
        }

        // ===== CastSpell realloc =====

        test("CastSpell reallocs instanceId") {
            val (b, game, counter) = base.startGameAtMain1()
            base.playLand(b) ?: error("playLand failed at seed 42")
            b.snapshotFromGame(game)

            val player = b.getPlayer(1) ?: error("Player 1 not found")
            val creature = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: error("No creature in hand at seed 42")
            val origInstanceId = b.getOrAllocInstanceId(creature.id)
            val forgeCardId = creature.id

            base.castCreature(b) ?: error("castCreature failed at seed 42")
            base.postAction(game, b, counter)

            val newInstanceId = b.getOrAllocInstanceId(forgeCardId)
            origInstanceId shouldNotBe newInstanceId
        }

        // ===== Resolve: no realloc =====

        test("Resolve keeps same instanceId") {
            val (b, game, counter) = base.startGameAtMain1()
            base.playLand(b) ?: error("playLand failed at seed 42")
            b.snapshotFromGame(game)

            val player = b.getPlayer(1) ?: error("Player 1 not found")
            val creature = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: error("No creature in hand at seed 42")
            val forgeCardId = creature.id

            base.castCreature(b) ?: error("castCreature failed at seed 42")
            base.postAction(game, b, counter)

            val stackInstanceId = b.getOrAllocInstanceId(forgeCardId)
            b.snapshotFromGame(game)

            base.passPriority(b)
            base.postAction(game, b, counter)

            val bfInstanceId = b.getOrAllocInstanceId(forgeCardId)
            bfInstanceId shouldBe stackInstanceId
        }

        // ===== Limbo accumulation =====

        fun advanceToNextMainPhase(b: leyline.game.GameBridge) {
            val game = b.getGame()!!
            var lastId: String? = null
            repeat(80) {
                if (game.isGameOver) return
                val pending = awaitFreshPending(b, lastId, timeoutMs = 5_000) ?: return
                if ((pending.state.phase == "MAIN1" || pending.state.phase == "MAIN2") &&
                    game.phaseHandler.turn > 1 &&
                    game.phaseHandler.playerTurn == b.getPlayer(1)
                ) {
                    return
                }
                b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                lastId = pending.actionId
            }
        }

        test("Limbo grows across multiple plays") {
            val (b, game, counter) = base.startGameAtMain1()

            val player = b.getPlayer(1) ?: error("Player 1 not found")
            val lands = player.getZone(forge.game.zone.ZoneType.Hand).cards.filter { it.isLand }
            if (lands.size < 2) return@test

            val land1 = lands[0]
            val origId1 = b.getOrAllocInstanceId(land1.id)
            base.playLand(b) ?: error("playLand failed at seed 42")
            base.postAction(game, b, counter)
            b.snapshotFromGame(game)

            b.getLimboInstanceIds().size shouldBe 1
            b.getLimboInstanceIds().shouldContain(origId1)

            advanceToNextMainPhase(b)

            val player2 = b.getPlayer(1) ?: error("Player 1 not found")
            val land2 = player2.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
            val origId2 = b.getOrAllocInstanceId(land2.id)

            val pending = awaitFreshPending(b, null) ?: error("No pending action available")
            b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(land2.id))
            awaitFreshPending(b, pending.actionId)

            base.postAction(game, b, counter)

            val limbo = b.getLimboInstanceIds()
            limbo.size shouldBeGreaterThanOrEqual 2
            limbo.shouldContain(origId1)
            limbo.shouldContain(origId2)
        }

        test("Limbo zone in GSM contains all retired ids") {
            val (b, game, counter) = base.startGameAtMain1()

            val player = b.getPlayer(1) ?: error("Player 1 not found")
            val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
            val origInstanceId = b.getOrAllocInstanceId(land.id)

            base.playLand(b) ?: error("playLand failed at seed 42")
            val gsm = base.postAction(game, b, counter).gsm

            assertLimboContains(gsm, origInstanceId)
        }

        test("no diffDeleted on retirement") {
            val (b, game, counter) = base.startGameAtMain1()

            val player = b.getPlayer(1) ?: error("Player 1 not found")
            val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
            val origInstanceId = b.getOrAllocInstanceId(land.id)

            base.playLand(b) ?: error("playLand failed at seed 42")
            val gsm = base.postAction(game, b, counter).gsm

            gsm.diffDeletedInstanceIdsList.contains(origInstanceId).shouldBeFalse()
        }

        // ===== ObjectIdChanged consistency =====

        test("ObjectIdChanged consistency") {
            val (b, game, counter) = base.startGameAtMain1()

            val player = b.getPlayer(1) ?: error("Player 1 not found")
            val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
            val origInstanceId = b.getOrAllocInstanceId(land.id)
            val forgeCardId = land.id

            base.playLand(b) ?: error("playLand failed at seed 42")
            val gsm = base.postAction(game, b, counter).gsm
            val newInstanceId = b.getOrAllocInstanceId(forgeCardId)

            val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
            oic.detailInt("orig_id") shouldBe origInstanceId
            oic.detailInt("new_id") shouldBe newInstanceId
            oic.affectedIdsList.shouldContain(origInstanceId)
        }
    })
