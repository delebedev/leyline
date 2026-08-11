package leyline.copilot

import forge.game.card.CounterEnumType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.InstanceId
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Round-trip fidelity: serialize a running game's Full GSM, hydrate a second
 * standalone game from it, and compare the hydrated Forge state against the
 * source Forge state on every field the serializer claims to carry.
 */
@Suppress("MissingAssertSoftly")
class SnapshotHydrationTest :
    SessionTest({

        test("hydrated game matches source on zones, flags, counters, life, ids") {
            val pzl =
                """
                [metadata]
                Name:Snapshot Round Trip
                Goal:Win
                Turns:5
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=17
                AILife=9

                humanhand=Lightning Bolt
                humanbattlefield=Mountain|Tapped;Mountain;Goblin Fireslinger|SummonSick|Counters:P1P1=2
                humangraveyard=Shock
                aibattlefield=Raging Goblin
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent()
            startPuzzleRaw(pzl)

            val sourceBridge = harness.bridge
            val sourceGame = sourceBridge.getGame().shouldNotBeNull()
            val snap = GsmSnapshot.capture(sourceGame, sourceBridge, "roundtrip", 0)
            val gsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "roundtrip", sourceBridge, viewingSeatId = 1)
                    .gsm

            val hydrated =
                SnapshotHydration.hydrate(
                    gsm = gsm,
                    consultSeat = 1,
                    cardRepository = TestCardRegistry.repo,
                )
            try {
                val hydratedGame = hydrated.getGame().shouldNotBeNull()

                fun names(
                    playerIndex: Int,
                    zone: ForgeZoneType,
                    game: forge.game.Game,
                ) = game.players[playerIndex]
                    .getZone(zone)
                    .cards
                    .map { it.name }
                    .sorted()

                for (playerIndex in 0..1) {
                    hydratedGame.players[playerIndex].life shouldBe sourceGame.players[playerIndex].life
                    for (zone in listOf(ForgeZoneType.Battlefield, ForgeZoneType.Graveyard)) {
                        names(playerIndex, zone, hydratedGame) shouldBe names(playerIndex, zone, sourceGame)
                    }
                }
                names(0, ForgeZoneType.Hand, hydratedGame) shouldBe names(0, ForgeZoneType.Hand, sourceGame)

                val hydratedBattlefield = hydratedGame.players[0].getZone(ForgeZoneType.Battlefield).cards
                hydratedBattlefield.count { it.name == "Mountain" && it.isTapped } shouldBe 1
                val goblin = hydratedBattlefield.first { it.name == "Goblin Fireslinger" }
                goblin.isSick shouldBe true
                goblin.getCounters(CounterEnumType.P1P1) shouldBe 2

                hydratedGame.phaseHandler.phase.toString() shouldBe sourceGame.phaseHandler.phase.toString()

                // Id space: every visible source Card in a carried zone resolves
                // through the hydrated registry to a same-name card.
                val hydratedCardsByForgeId =
                    hydratedGame.players
                        .flatMap { p ->
                            listOf(ForgeZoneType.Battlefield, ForgeZoneType.Hand, ForgeZoneType.Graveyard)
                                .flatMap { z -> p.getZone(z).cards }
                        }.associateBy { it.id }
                val zonesById = gsm.zonesList.associateBy { it.zoneId }
                val carried = setOf(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard)
                val sourceVisible =
                    gsm.gameObjectsList.filter { obj ->
                        obj.type == GameObjectType.Card &&
                            zonesById[obj.zoneId]?.type in carried &&
                            TestCardRegistry.repo.findNameByGrpId(obj.grpId) != null
                    }
                sourceVisible.map { it.instanceId }.shouldContain(
                    gsm.gameObjectsList
                        .first { TestCardRegistry.repo.findNameByGrpId(it.grpId) == "Lightning Bolt" }
                        .instanceId,
                )
                for (obj in sourceVisible) {
                    val forgeId = hydrated.ids.getForgeCardId(InstanceId(obj.instanceId)).shouldNotBeNull()
                    hydratedCardsByForgeId shouldContainKey forgeId.value
                    hydratedCardsByForgeId
                        .getValue(forgeId.value)
                        .name shouldBe TestCardRegistry.repo.findNameByGrpId(obj.grpId)
                }
            } finally {
                hydrated.teardownResources()
            }
        }
    })
