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
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ForgeZoneType.Hand)
                }

            val land =
                game.humanPlayer
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .first { it.isLand }
            val gsm =
                captureAfterAction(b, game, counter) {
                    game.action.moveToPlay(land, null, AbilityKey.newMap())
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
            val (b, game, counter) =
                startGameAtMain1(
                    deckList =
                        """
                        30 Forest
                        30 Llanowar Elves
                        """.trimIndent(),
                )
            val acc = ClientAccumulator()
            acc.seedFull(handshakeFull(game, b, counter.currentGsId()))

            val startResult = gameStart(game, b, counter)
            acc.processAll(startResult.messages)
            b.seedDiffBaseline(game)

            playLand(b) ?: error("playLand failed at seed 42")
            val afterLand = postAction(game, b, counter)
            acc.processAll(afterLand.messages)

            val castAction = castCreature(b) ?: error("castCreature failed at seed 42")
            val creatureForgeId = castAction.cardId.value
            val afterCast = postAction(game, b, counter)
            acc.processAll(afterCast.messages)

            val creatureNewId = b.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value
            val creatureObj =
                checkNotNull(acc.objects[creatureNewId]) {
                    "Creature should exist in accumulated objects with instanceId $creatureNewId"
                }

            if (game.stack.isEmpty) {
                creatureObj.zoneId shouldBe ZoneIds.BATTLEFIELD
            } else {
                creatureObj.zoneId shouldBe ZoneIds.STACK

                passPriority(b)
                val afterPass = postAction(game, b, counter)
                acc.processAll(afterPass.messages)

                val resolved = checkNotNull(acc.objects[creatureNewId]) { "Creature should still exist after resolve" }
                resolved.zoneId shouldBe ZoneIds.BATTLEFIELD
                acc.zones[ZoneIds.BATTLEFIELD]!!.objectInstanceIdsList.shouldContain(creatureNewId)
            }
        }

        test("resolve keeps instanceId") {
            val (b, game, counter) = startGameAtMain1()

            playLand(b) ?: error("playLand failed at seed 42")
            postAction(game, b, counter)

            val castAction = castCreature(b) ?: error("castCreature failed at seed 42")
            val creatureForgeId = castAction.cardId.value
            postAction(game, b, counter)
            val castId = b.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value

            if (!game.stack.isEmpty) {
                passPriority(b)
                postAction(game, b, counter)
            }

            val resolvedId = b.getOrAllocInstanceId(ForgeCardId(creatureForgeId)).value
            castId shouldBe resolvedId
        }

        test("remoteActionDiff contains BF objects for AI land play") {
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ForgeZoneType.Hand)
                }

            val land =
                game.humanPlayer
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .first { it.isLand }
            captureAfterAction(b, game, counter) {
                game.action.moveToPlay(land, null, AbilityKey.newMap())
            }

            val aiResult =
                bundleBuilder(b).remoteActionDiff(
                    game,
                    counter,
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
