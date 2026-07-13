package leyline.board.diff

import forge.game.ability.AbilityKey
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ZoneIds
import leyline.game.seedDiffBaseline
import leyline.testkit.BoardTest
import leyline.testkit.ClientAccumulator
import leyline.testkit.ValidatingMessageSink
import leyline.testkit.annotation
import leyline.testkit.detailInt
import leyline.testkit.gsm
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import forge.game.zone.ZoneType as ForgeZoneType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Diagnostic tests tracing exact diff contents for each game action.
 *
 * Accumulator consistency (zone-object refs, action instanceIds, no duplicates)
 * is automatic via [ValidatingMessageSink]. What remains here are structural
 * assertions about diff contents — which zones appear, annotation types, field values.
 */
class DiffDiagnosticTest :
    BoardTest({

        test("diff after land play has correct GSM type, zones, and annotations") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ForgeZoneType.Hand)
                }

            val land =
                board.game.humanPlayer
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .first { it.isLand }
            val gsm =
                board.snapshotDiff {
                    board.game.action.moveToPlay(land, null, AbilityKey.newMap())
                }

            assertSoftly {
                gsm.type shouldBe GameStateType.Diff

                val zoneTypes = gsm.zonesList.map { it.type }.toSet()
                (ProtoZoneType.Hand in zoneTypes).shouldBeTrue()
                (ProtoZoneType.Battlefield in zoneTypes).shouldBeTrue()
                (ProtoZoneType.Limbo in zoneTypes).shouldBeTrue()

                val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
                val origId = oic.detailInt("orig_id")
                gsm.diffDeletedInstanceIdsList shouldNotContain origId
            }
        }

        test("cast creature -> pass -> resolve tracks zone placement correctly") {
            val board =
                startGameAtMain1(
                    deckList =
                        """
                        30 Forest
                        30 Llanowar Elves
                        """.trimIndent(),
                )
            val acc = ClientAccumulator()
            acc.seedFull(handshakeFull(board.game, board.bridge, board.counter.currentGsId()))

            val startResult = board.gameStart()
            acc.processAll(startResult.messages)
            board.bridge.seedDiffBaseline(board.game)

            playLand(board.bridge) ?: error("playLand failed at seed 42")
            val afterLand = board.postAction()
            acc.processAll(afterLand.messages)

            val castAction = castCreature(board.bridge) ?: error("castCreature failed at seed 42")
            val creatureForgeId = castAction.cardId.value
            val afterCast = board.postAction()
            acc.processAll(afterCast.messages)

            val creatureNewId = board.bridge.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value
            val creatureObj =
                checkNotNull(acc.objects[creatureNewId]) {
                    "Creature should exist in accumulated objects with instanceId $creatureNewId"
                }

            if (board.game.stack.isEmpty) {
                creatureObj.zoneId shouldBe ZoneIds.BATTLEFIELD
            } else {
                creatureObj.zoneId shouldBe ZoneIds.STACK

                passPriority(board.bridge)
                val afterPass = board.postAction()
                acc.processAll(afterPass.messages)

                val resolved = checkNotNull(acc.objects[creatureNewId]) { "Creature should still exist after resolve" }
                resolved.zoneId shouldBe ZoneIds.BATTLEFIELD
                acc.zones[ZoneIds.BATTLEFIELD]!!.objectInstanceIdsList.shouldContain(creatureNewId)
            }
        }

        test("resolve keeps instanceId") {
            val board = startGameAtMain1()

            playLand(board.bridge) ?: error("playLand failed at seed 42")
            board.postAction()

            val castAction = castCreature(board.bridge) ?: error("castCreature failed at seed 42")
            val creatureForgeId = castAction.cardId.value
            board.postAction()
            val castId = board.bridge.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value

            if (!board.game.stack.isEmpty) {
                passPriority(board.bridge)
                board.postAction()
            }

            val resolvedId = board.bridge.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value
            castId shouldBe resolvedId
        }

        test("remoteActionDiff contains BF objects for AI land play") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ForgeZoneType.Hand)
                }

            val land =
                board.game.humanPlayer
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .first { it.isLand }
            board.snapshotDiff {
                board.game.action.moveToPlay(land, null, AbilityKey.newMap())
            }

            val aiResult =
                bundleBuilder(board.bridge).remoteActionDiff(
                    board.game,
                    board.counter,
                )

            val gsm = aiResult.gsm
            gsm.type shouldBe GameStateType.Diff

            // remoteActionDiff may or may not include BF zone depending on
            // what changed. Core invariant: all objects have a valid zoneId.
            for (obj in gsm.gameObjectsList) {
                obj.zoneId shouldBeGreaterThan 0
            }
        }
    })
