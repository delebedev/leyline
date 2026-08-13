package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.mapping.StateMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.StackEntry
import leyline.game.snapshot.StackSnapshot
import leyline.game.state.MechanicSourceFacts
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.aiPlayer
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Shape tests for [leyline.game.mapping.StateMapper] output — zone visibility, timers, player info.
 * Board-based (no game loop needed).
 */
class StateMapperShapeTest :
    BoardTest({

        test("full state has timers") {
            val (b, game) = startWithBoard { _, _, _ -> }

            val snap = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val gs =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        Board.TEST_MATCH_ID,
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            gs.timersCount shouldBeGreaterThanOrEqual 2
            val timer1 = gs.timersList.first { it.timerId == 1 }
            val timer2 = gs.timersList.first { it.timerId == 2 }
            assertSoftly {
                timer1.type shouldBe Messages.TimerType.Inactivity_a5e2
                timer2.type shouldBe Messages.TimerType.Inactivity_a5e2
                timer1.durationSec shouldBeGreaterThan 0
            }
        }

        test("zone visibility matches compatibility shape") {
            val (b, game) =
                startWithBoard { g, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Graveyard)
                    addCard("Mountain", human, ZoneType.Graveyard)
                    addCard("Grizzly Bears", human, ZoneType.Exile)
                    addCard("Llanowar Elves", human, ZoneType.Exile)
                }

            val snap = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val gs =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        Board.TEST_MATCH_ID,
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            val byId = gs.zonesList.associateBy { it.zoneId }
            val objectsById = gs.gameObjectsList.associateBy { it.instanceId }
            val zoneGrpIds: (Int) -> List<Int> = { zoneId ->
                byId.getValue(zoneId).objectInstanceIdsList.map { objectsById.getValue(it).grpId }
            }
            assertSoftly {
                byId[ZoneIds.SUPPRESSED]!!.visibility shouldBe Messages.Visibility.Public
                byId[ZoneIds.PENDING]!!.visibility shouldBe Messages.Visibility.Public
                byId[ZoneIds.P1_SIDEBOARD]!!.visibility shouldBe Messages.Visibility.Private
                byId[ZoneIds.P2_SIDEBOARD]!!.visibility shouldBe Messages.Visibility.Private
                zoneGrpIds(ZoneIds.P1_GRAVEYARD) shouldContainExactly
                    listOf(
                        b.cardRepository.findGrpIdByName("Mountain")!!,
                        b.cardRepository.findGrpIdByName("Forest")!!,
                    )
                zoneGrpIds(ZoneIds.EXILE) shouldContainExactly
                    listOf(
                        b.cardRepository.findGrpIdByName("Llanowar Elves")!!,
                        b.cardRepository.findGrpIdByName("Grizzly Bears")!!,
                    )
            }

            val gyObjects =
                gs.gameObjectsList.filter { obj ->
                    obj.zoneId == ZoneIds.P1_GRAVEYARD || obj.zoneId == ZoneIds.P2_GRAVEYARD
                }
            for (obj in gyObjects) {
                obj.visibility shouldBe Messages.Visibility.Public
            }

            val handObjects =
                gs.gameObjectsList.filter { obj ->
                    obj.zoneId == ZoneIds.P1_HAND || obj.zoneId == ZoneIds.P2_HAND
                }
            for (obj in handObjects) {
                obj.visibility shouldBe Messages.Visibility.Private
            }
        }

        test("full state redacts opponent sideboard contents") {
            val (b, game) =
                startWithBoard { _, human, ai ->
                    addCard("Forest", human, ZoneType.Sideboard)
                    addCard("Mountain", ai, ZoneType.Sideboard)
                }

            val snap = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val gs =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        Board.TEST_MATCH_ID,
                        b,
                        viewingSeatId = 1,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            val byId = gs.zonesList.associateBy { it.zoneId }
            assertSoftly {
                byId[ZoneIds.P1_SIDEBOARD]!!.objectInstanceIdsCount shouldBe 1
                byId[ZoneIds.P2_SIDEBOARD]!!.objectInstanceIdsCount shouldBe 0
                gs.gameObjectsList.count { it.zoneId == ZoneIds.P1_SIDEBOARD } shouldBe 1
                gs.gameObjectsList.count { it.zoneId == ZoneIds.P2_SIDEBOARD } shouldBe 0
            }
        }

        test("diff state projects changed public zones and redacts opponent sideboard contents") {
            val (b, game) =
                startWithBoard { _, human, ai ->
                    addCard("Mountain", ai, ZoneType.Sideboard)
                    addCard("Forest", human, ZoneType.Graveyard)
                    addCard("Grizzly Bears", human, ZoneType.Exile)
                }
            val prev = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            addCard("Forest", game.aiPlayer, ZoneType.Sideboard)
            addCard("Mountain", game.humanPlayer, ZoneType.Graveyard)
            addCard("Llanowar Elves", game.humanPlayer, ZoneType.Exile)
            addCard("Grizzly Bears", game.humanPlayer, ZoneType.Graveyard)
            addCard("Forest", game.humanPlayer, ZoneType.Exile)
            val cur = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 2)

            val gs =
                StateMapper
                    .buildDiff(
                        prev = prev,
                        cur = cur,
                        events = FrameEventLog.EMPTY,
                        gameStateId = 2,
                        matchId = Board.TEST_MATCH_ID,
                        bridge = b,
                        viewingSeatId = 1,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            val opponentSideboard = gs.zonesList.single { it.zoneId == ZoneIds.P2_SIDEBOARD }
            val objectsById = gs.gameObjectsList.associateBy { it.instanceId }
            val zoneInstanceIds: (Int) -> List<Int> = { zoneId ->
                gs.zonesList.single { it.zoneId == zoneId }.objectInstanceIdsList
            }
            val graveyardIds = zoneInstanceIds(ZoneIds.P1_GRAVEYARD)
            val exileIds = zoneInstanceIds(ZoneIds.EXILE)

            assertSoftly {
                opponentSideboard.objectInstanceIdsCount shouldBe 0
                gs.gameObjectsList.count { it.zoneId == ZoneIds.P2_SIDEBOARD } shouldBe 0
                objectsById.getValue(graveyardIds[0]).grpId shouldBe b.cardRepository.findGrpIdByName("Grizzly Bears")
                objectsById.getValue(graveyardIds[1]).grpId shouldBe b.cardRepository.findGrpIdByName("Mountain")
                objectsById.getValue(exileIds[0]).grpId shouldBe b.cardRepository.findGrpIdByName("Forest")
                objectsById.getValue(exileIds[1]).grpId shouldBe b.cardRepository.findGrpIdByName("Llanowar Elves")
            }
        }

        test("buildFromSnapshot produces valid state") {
            val (b, game) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Llanowar Elves", human, ZoneType.Hand)
                }

            val snap = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val gs =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        Board.TEST_MATCH_ID,
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            gs.zonesCount shouldBeGreaterThan 0
            gs.gameObjectsCount shouldBeGreaterThan 0

            val handZone = gs.zonesList.find { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
            assertSoftly {
                handZone.shouldNotBeNull()
                handZone.objectInstanceIdsCount shouldBe 3
                gs.hasTurnInfo().shouldBeTrue()
            }
        }

        test("game objects have card type fields") {
            val (b, game) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Llanowar Elves", human, ZoneType.Hand)
                }

            val snap = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val gs =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        Board.TEST_MATCH_ID,
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            val handZone = gs.zonesList.first { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
            val handInstanceIds = handZone.objectInstanceIdsList.toSet()
            val handObjects = gs.gameObjectsList.filter { it.instanceId in handInstanceIds }
            handObjects.shouldNotBeEmpty()

            for (obj in handObjects) {
                obj.cardTypesCount shouldBeGreaterThan 0
            }

            val lands =
                handObjects.filter {
                    it.cardTypesList.contains(Messages.CardType.Land_a80b)
                }
            lands.shouldNotBeEmpty()
            for (land in lands) {
                land.superTypesList shouldContain Messages.SuperType.Basic
                land.subtypesList shouldContain Messages.SubType.Forest
            }

            val creatures =
                handObjects.filter {
                    it.cardTypesList.contains(Messages.CardType.Creature)
                }
            creatures.shouldNotBeEmpty()
            for (c in creatures) {
                c.hasPower().shouldBeTrue()
                c.hasToughness().shouldBeTrue()
            }
        }

        test("player info has timer ids") {
            val (b, game) = startWithBoard { _, _, _ -> }

            val snap = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val gs =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        1,
                        Board.TEST_MATCH_ID,
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            for (player in gs.playersList) {
                player.timerIdsCount shouldBeGreaterThan 0
                player.timerIdsList[0] shouldBe player.systemSeatNumber
            }
        }

        test("resolved ability is deleted when the current stack snapshot still carries it") {
            val (b, game) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val base = GsmSnapshot.capture(game, b, Board.TEST_MATCH_ID, 1)
            val sourceCardId = base.boundCards.keys.single()
            val ability =
                StackEntry(
                    forgeCardId = sourceCardId,
                    controller = SeatId(1),
                    owner = SeatId(1),
                    grpId = 12345,
                    sourceCardGrpId = 12345,
                    isSpell = false,
                    targets = emptyList(),
                    forgeAbilityId = 777,
                )

            fun snapshot(gameStateId: Int) =
                GsmSnapshot.forTest(
                    matchId = base.matchId,
                    gameStateId = gameStateId,
                    seats = base.seats,
                    zones = base.zones,
                    boundCards = base.boundCards,
                    stack = StackSnapshot(listOf(ability)),
                    phase = base.phase,
                    combat = base.combat,
                    abilityWordEntries = base.abilityWordEntries,
                    pendingTriggers = base.pendingTriggers,
                    capturedAt = base.capturedAt,
                    dayTime = base.dayTime,
                    activePlayerSpellsCastThisTurn = base.activePlayerSpellsCastThisTurn,
                )
            val previous = snapshot(1)
            val current = snapshot(2)
            val abilityIid = FrameIdResolver(b.projectionIdentityWorkspace()).triggerStackAbilityIid(777).value

            val gsm =
                StateMapper
                    .buildDiff(
                        prev = previous,
                        cur = current,
                        events =
                            FrameEventLog(
                                listOf(
                                    GameEvent.SpellResolved(
                                        cardId = ForgeCardId(sourceCardId.value),
                                        hasFizzled = false,
                                        isAbility = true,
                                        abilityForgeId = 777,
                                    ),
                                ),
                            ),
                        gameStateId = 2,
                        matchId = Board.TEST_MATCH_ID,
                        bridge = b,
                        viewingSeatId = 1,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                        mechanicSourceFacts = MechanicSourceFacts(),
                    ).gsm

            assertSoftly {
                gsm.diffDeletedInstanceIdsList shouldContainExactly listOf(abilityIid)
                gsm.gameObjectsList.map { it.instanceId } shouldNotContain abilityIid
                gsm.zonesList
                    .single { it.zoneId == ZoneIds.STACK }
                    .objectInstanceIdsList shouldBe emptyList()
            }
        }
    })
