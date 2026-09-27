package leyline.game.mapping

import forge.game.keyword.Keyword
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.codes.KeywordGrpIds
import leyline.game.seedDiffBaseline
import leyline.game.snapshot.SnapshotCapture
import leyline.game.state.EffectTracker
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.aiPlayer
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/**
 * Verifies that [ObjectMapper.buildFromSnapshot] produces correct [GameObjectInfo] protos.
 *
 * Uses [Board.startWithBoard] for a synchronous board setup (~0.01s/test).
 */
class ObjectMapperSnapshotTest :
    BoardTest({

        test("off-battlefield hand card: snapshot fields are correct") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }
            val card =
                game.humanPlayer.hand.card("Grizzly Bears")
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.seedDiffBaseline(game)
            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)

            val fromSnap = ObjectMapper.buildFromSnapshot(cardSnap, instanceId, ZoneIds.P1_HAND, 1, b.cardProto)

            assertSoftly {
                fromSnap.instanceId shouldBe instanceId
                fromSnap.zoneId shouldBe ZoneIds.P1_HAND
                fromSnap.ownerSeatId shouldBe 1
                fromSnap.controllerSeatId shouldBe 1
                fromSnap.isTapped shouldBe false
            }
        }

        test("on-battlefield creature: P/T + tapped + sickness captured") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val card =
                game.humanPlayer.battlefield.card("Grizzly Bears")
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.seedDiffBaseline(game)
            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)

            assertSoftly {
                cardSnap.isOnBattlefield shouldBe true
                cardSnap.netPower shouldNotBe null
                cardSnap.netToughness shouldNotBe null
            }

            val fromSnap =
                ObjectMapper.buildFromSnapshot(
                    cardSnap,
                    instanceId,
                    ZoneIds.BATTLEFIELD,
                    1,
                    b.cardProto,
                    Visibility.Public,
                )

            assertSoftly {
                fromSnap.power.value shouldBe card.netPower
                fromSnap.toughness.value shouldBe card.netToughness
                fromSnap.isTapped shouldBe card.isTapped
            }
        }

        test("parameterized keyword grants project exact unique ability identities") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val card = game.humanPlayer.battlefield.card("Grizzly Bears")
            val forgeCardId = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(forgeCardId).value
            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(forgeCardId)
            val keywords =
                listOf(
                    Keyword.getInstance("Hexproof:Blue"),
                    Keyword.getInstance("Hexproof:Black"),
                    Keyword.getInstance("Protection:Blue"),
                )
            val keywordSnapshot =
                mapOf(
                    instanceId to
                        keywords.map { keyword ->
                            EffectTracker.KeywordEntry(
                                timestamp = 1L,
                                staticId = 5L,
                                keyword = keyword.title,
                                abilityGrpId = KeywordGrpIds.forKeyword(keyword.title, keyword.keyword.toString()),
                            )
                        } + EffectTracker.KeywordEntry(timestamp = 2L, staticId = 6L, keyword = "Flying"),
                )

            val projected =
                ObjectMapper.buildFromSnapshot(
                    cardSnap,
                    instanceId,
                    ZoneIds.BATTLEFIELD,
                    1,
                    b.cardProto,
                    Visibility.Public,
                    keywordSnapshot,
                )

            projected
                .uniqueAbilitiesList
                .map { it.grpId }
                .filter { it in setOf(8, 186, 192, 193) }
                .sorted() shouldBe
                listOf(8, 186, 192, 193)
        }

        test("graveyard card: visibility and zone are correct") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Graveyard)
                }
            val card =
                game.humanPlayer.graveyard.card("Grizzly Bears")
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.seedDiffBaseline(game)
            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)

            cardSnap.isOnBattlefield shouldBe false

            val fromSnap =
                ObjectMapper.buildFromSnapshot(
                    cardSnap,
                    instanceId,
                    ZoneIds.P1_GRAVEYARD,
                    1,
                    b.cardProto,
                    Visibility.Public,
                )

            assertSoftly {
                fromSnap.zoneId shouldBe ZoneIds.P1_GRAVEYARD
                fromSnap.visibility shouldBe Visibility.Public
            }
        }

        test("planeswalker loyalty is captured in snapshot") {
            TestCardRegistry.ensureCardRegistered("Chandra, Torch of Defiance")

            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Chandra, Torch of Defiance", human, ZoneType.Battlefield)
                }
            val card =
                game.humanPlayer.battlefield.card("Chandra, Torch of Defiance")
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.seedDiffBaseline(game)
            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)

            val fromSnap =
                ObjectMapper.buildFromSnapshot(
                    cardSnap,
                    instanceId,
                    ZoneIds.BATTLEFIELD,
                    1,
                    b.cardProto,
                    Visibility.Public,
                )

            fromSnap.loyalty.value shouldBe card.currentLoyalty
        }

        test("buildFromSnapshot owner/controller seats correct for AI cards") {
            val (b, game, _) =
                startWithBoard { _, _, ai ->
                    addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                }
            val card =
                game.aiPlayer.battlefield.card("Grizzly Bears")
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.seedDiffBaseline(game)
            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)

            assertSoftly {
                cardSnap.owner shouldBe SeatId(2)
                cardSnap.controller shouldBe SeatId(2)
            }

            val fromSnap =
                ObjectMapper.buildFromSnapshot(
                    cardSnap,
                    instanceId,
                    ZoneIds.BATTLEFIELD,
                    2,
                    b.cardProto,
                    Visibility.Public,
                )

            assertSoftly {
                fromSnap.ownerSeatId shouldBe 2
                fromSnap.controllerSeatId shouldBe 2
            }
        }
    })
