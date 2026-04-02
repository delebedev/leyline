package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.snapshotFromGame

/**
 * InstanceId reallocation on zone transfer and Limbo zone accumulation.
 *
 * Real server allocates a new instanceId every time a card changes zones
 * (except Stack→Battlefield on resolve). The old instanceId is retired to Limbo
 * via objectInstanceIds in the Limbo ZoneInfo — no GameObjectInfo is emitted for it.
 * Limbo is monotonically growing — retired IDs are never removed.
 */
class InstanceIdReallocTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // PlayLand realloc test moved to LandManaTest

        test("zone transfer reallocs instanceId (Destroy)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val origInstanceId = b.getOrAllocInstanceId(ForgeCardId(creature.id))
            val cardId = creature.id

            base.captureAfterAction(b, game, counter) {
                game.action.destroy(creature, null, false, AbilityKey.newMap())
            }
            val newInstanceId = b.getOrAllocInstanceId(ForgeCardId(cardId))

            origInstanceId shouldNotBe newInstanceId
            b.getLimboInstanceIds().shouldContain(origInstanceId)
        }

        // ===== Engine-dependent tests =====

        test("Resolve keeps same instanceId") {
            val (b, game, counter) = base.startGameAtMain1()
            base.playLand(b) ?: error("playLand failed at seed 42")
            b.snapshotFromGame(game)

            val player = b.getPlayer(SeatId(1))!!
            val creature = player.getZone(ZoneType.Hand).cards.first { it.isCreature }
            val cardId = creature.id

            base.castCreature(b) ?: error("castCreature failed at seed 42")
            base.postAction(game, b, counter)

            val stackInstanceId = b.getOrAllocInstanceId(ForgeCardId(cardId))
            b.snapshotFromGame(game)

            base.passPriority(b)
            base.postAction(game, b, counter)

            val bfInstanceId = b.getOrAllocInstanceId(ForgeCardId(cardId))
            bfInstanceId shouldBe stackInstanceId
        }

        // Limbo grows test moved to LandManaTest
    })
