package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.PlayerAction
import leyline.bridge.SeatId
import leyline.game.awaitFreshPending
import leyline.game.mapper.ZoneIds
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

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

        test("PlayLand realloc — new instanceId, Limbo retirement, ObjectIdChanged") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Plains", human, ZoneType.Hand)
            }

            val player = b.getPlayer(SeatId(1))!!
            val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
            val origInstanceId = b.getOrAllocInstanceId(ForgeCardId(land.id))
            val forgeCardId = land.id

            val gsm = base.captureAfterAction(b, game, counter) {
                game.action.moveToPlay(land, null, AbilityKey.newMap())
            }
            val newInstanceId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

            origInstanceId shouldNotBe newInstanceId

            assertSoftly {
                // Old instanceId retired to Limbo (bridge tracking)
                b.getLimboInstanceIds().shouldContain(origInstanceId)

                // Limbo zone in GSM contains retired id
                assertLimboContains(gsm, origInstanceId.value)

                // No GameObjectInfo for Limbo objects in diff
                gsm.gameObjectsList.filter { it.zoneId == ZoneIds.LIMBO }.shouldBeEmpty()

                // Old instanceId NOT in diffDeletedInstanceIds
                gsm.diffDeletedInstanceIdsList.contains(origInstanceId.value).shouldBeFalse()

                // ObjectIdChanged annotation: orig → new
                val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
                oic.detailInt("orig_id") shouldBe origInstanceId.value
                oic.detailInt("new_id") shouldBe newInstanceId.value
                oic.affectedIdsList.shouldContain(origInstanceId.value)
            }
        }

        test("zone transfer reallocs instanceId (Destroy)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val origInstanceId = b.getOrAllocInstanceId(ForgeCardId(creature.id))
            val forgeCardId = creature.id

            base.captureAfterAction(b, game, counter) {
                game.action.destroy(creature, null, false, AbilityKey.newMap())
            }
            val newInstanceId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))

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
            val forgeCardId = creature.id

            base.castCreature(b) ?: error("castCreature failed at seed 42")
            base.postAction(game, b, counter)

            val stackInstanceId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))
            b.snapshotFromGame(game)

            base.passPriority(b)
            base.postAction(game, b, counter)

            val bfInstanceId = b.getOrAllocInstanceId(ForgeCardId(forgeCardId))
            bfInstanceId shouldBe stackInstanceId
        }

        test("Limbo grows across multiple plays") {
            val (b, game, counter) = base.startGameAtMain1()

            val player = b.getPlayer(SeatId(1))!!
            val lands = player.getZone(ZoneType.Hand).cards.filter { it.isLand }
            (lands.size >= 2) shouldBe true

            val land1 = lands[0]
            val origId1 = b.getOrAllocInstanceId(ForgeCardId(land1.id))
            base.playLand(b) ?: error("playLand failed at seed 42")
            base.postAction(game, b, counter)
            b.snapshotFromGame(game)

            b.getLimboInstanceIds().size shouldBe 1
            b.getLimboInstanceIds().shouldContain(origId1)

            // Advance to next human main phase (turn 2+)
            val game2 = b.getGame()!!
            var lastId: String? = null
            repeat(80) {
                if (game2.isGameOver) return@test
                val pending = awaitFreshPending(b, lastId, timeoutMs = 5_000) ?: return@test
                if ((pending.state.phase == "MAIN1" || pending.state.phase == "MAIN2") &&
                    game2.phaseHandler.turn > 1 &&
                    game2.phaseHandler.playerTurn == b.getPlayer(SeatId(1))
                ) {
                    // At next main phase — play second land
                    val player2 = b.getPlayer(SeatId(1))!!
                    val land2 = player2.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
                        ?: error("No land in hand for turn 2")
                    val origId2 = b.getOrAllocInstanceId(ForgeCardId(land2.id))

                    b.actionBridge.submitAction(pending.actionId, PlayerAction.PlayLand(ForgeCardId(land2.id)))
                    awaitFreshPending(b, pending.actionId)
                    base.postAction(game, b, counter)

                    val limbo = b.getLimboInstanceIds()
                    limbo.size shouldBeGreaterThanOrEqual 2
                    limbo.shouldContain(origId1)
                    limbo.shouldContain(origId2)
                    return@test
                }
                b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
                lastId = pending.actionId
            }
        }
    })
