package leyline.copilot

import forge.game.card.CounterEnumType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.InstanceId
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AttackState
import wotc.mtgo.gre.external.messaging.Messages.BlockInfo
import wotc.mtgo.gre.external.messaging.Messages.BlockState
import wotc.mtgo.gre.external.messaging.Messages.CardColor
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.Int32Value
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.PlayerInfo
import wotc.mtgo.gre.external.messaging.Messages.Step
import wotc.mtgo.gre.external.messaging.Messages.SubType
import wotc.mtgo.gre.external.messaging.Messages.TurnInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
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

        test("pre-turn snapshot uses decision player and a valid puzzle turn") {
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setTurnInfo(TurnInfo.newBuilder().setDecisionPlayer(2))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(20))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(2).setLifeTotal(20))
                    .build()

            val lines = SnapshotHydration.toPuzzleLines(gsm, 2, TestCardRegistry.repo)
            lines.filter { it.startsWith("ActivePlayer=") || it.startsWith("Turn=") } shouldBe
                listOf("ActivePlayer=P1", "Turn=1")

            val hydrated = SnapshotHydration.hydrate(gsm, 2, TestCardRegistry.repo)
            try {
                hydrated.getGame().shouldNotBeNull()
            } finally {
                hydrated.teardownResources()
            }
        }

        test("token attachment target hydrates as a Forge token") {
            val tokenGrpId = 990_001
            val tokenIid = 201
            val auraIid = 202
            val pacifismGrpId = TestCardRegistry.ensureCardRegistered("Pacifism")
            TestCardRegistry.repo.register(tokenGrpId, "Spider")
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setTurnInfo(TurnInfo.newBuilder().setActivePlayer(1).setTurnNumber(3))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(20))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(2).setLifeTotal(20))
                    .addZones(ZoneInfo.newBuilder().setZoneId(7).setType(ZoneType.Battlefield))
                    .addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(tokenIid)
                            .setGrpId(tokenGrpId)
                            .setType(GameObjectType.Token)
                            .setZoneId(7)
                            .setOwnerSeatId(2)
                            .setControllerSeatId(2)
                            .addCardTypes(CardType.Creature)
                            .addSubtypes(SubType.Spider)
                            .addColor(CardColor.Green_a3b0)
                            .setPower(Int32Value.newBuilder().setValue(1))
                            .setToughness(Int32Value.newBuilder().setValue(2)),
                    ).addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(auraIid)
                            .setGrpId(pacifismGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(7)
                            .setOwnerSeatId(1)
                            .setControllerSeatId(1),
                    ).addPersistentAnnotations(
                        AnnotationInfo
                            .newBuilder()
                            .setId(300)
                            .addType(AnnotationType.Attachment)
                            .setAffectorId(auraIid)
                            .addAffectedIds(tokenIid),
                    ).build()

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                val game = hydrated.bridge.getGame().shouldNotBeNull()
                val token =
                    game.players[1]
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .single { it.name == "Spider" }
                val aura =
                    game.players[0]
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .single { it.name == "Pacifism" }

                token.isToken shouldBe true
                token.basePower shouldBe 1
                token.baseToughness shouldBe 2
                aura.entityAttachedTo shouldBe token
                hydrated.fidelity.features
                    .first { it.feature == "attachments" }
                    .status shouldBe "carried"
            } finally {
                hydrated.bridge.teardownResources()
            }
        }

        test("token attachment source hydrates as a Forge attachment") {
            val tokenGrpId = 990_002
            val tokenIid = 201
            val targetIid = 202
            val targetGrpId = TestCardRegistry.ensureCardRegistered("Grizzly Bears")
            TestCardRegistry.repo.register(tokenGrpId, "Wicked Role")
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setTurnInfo(TurnInfo.newBuilder().setActivePlayer(1).setTurnNumber(3))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(20))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(2).setLifeTotal(20))
                    .addZones(ZoneInfo.newBuilder().setZoneId(7).setType(ZoneType.Battlefield))
                    .addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(tokenIid)
                            .setGrpId(tokenGrpId)
                            .setType(GameObjectType.Token)
                            .setZoneId(7)
                            .setOwnerSeatId(1)
                            .setControllerSeatId(1)
                            .addCardTypes(CardType.Enchantment)
                            .addSubtypes(SubType.Aura)
                            .addSubtypes(SubType.Role),
                    ).addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(targetIid)
                            .setGrpId(targetGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(7)
                            .setOwnerSeatId(2)
                            .setControllerSeatId(2),
                    ).addPersistentAnnotations(
                        AnnotationInfo
                            .newBuilder()
                            .setId(301)
                            .addType(AnnotationType.Attachment)
                            .setAffectorId(tokenIid)
                            .addAffectedIds(targetIid),
                    ).build()

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                val game = hydrated.bridge.getGame().shouldNotBeNull()
                val tokenCards = game.players[0].getZone(ForgeZoneType.Battlefield).cards
                val targetCards = game.players[1].getZone(ForgeZoneType.Battlefield).cards
                val token = tokenCards.single { it.name == "Wicked Role" }
                val target = targetCards.single { it.name == "Grizzly Bears" }

                token.isToken shouldBe true
                token.isAttachment shouldBe true
                token.entityAttachedTo shouldBe target
                hydrated.fidelity.features
                    .first { it.feature == "attachments" }
                    .status shouldBe "carried"
            } finally {
                hydrated.bridge.teardownResources()
            }
        }

        test("face-down card uses public characteristics without requiring its hidden identity") {
            val battlefieldZoneId = 7
            val faceDownIid = 201
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setTurnInfo(TurnInfo.newBuilder().setActivePlayer(1).setTurnNumber(3))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(20))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(2).setLifeTotal(20))
                    .addZones(ZoneInfo.newBuilder().setZoneId(battlefieldZoneId).setType(ZoneType.Battlefield))
                    .addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(faceDownIid)
                            .setGrpId(3)
                            .setType(GameObjectType.Card)
                            .setZoneId(battlefieldZoneId)
                            .setOwnerSeatId(2)
                            .setControllerSeatId(2)
                            .setIsFacedown(true)
                            .addCardTypes(CardType.Creature)
                            .setPower(Int32Value.newBuilder().setValue(5))
                            .setToughness(Int32Value.newBuilder().setValue(5)),
                    ).build()

            SnapshotHydration.toPuzzleLines(gsm, 1, TestCardRegistry.repo) shouldContain
                "p1battlefield=t:Face-down creature,P:5,T:5,Cost:0,Types:Creature,Keywords:,Image:|Id:201"

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                hydrated.bridge
                    .getGame()
                    .shouldNotBeNull()
                    .players[1]
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .single()
                    .let { card ->
                        card.name shouldBe "Face-down creature"
                        card.netPower shouldBe 5
                        card.netToughness shouldBe 5
                    }
                hydrated.fidelity.features
                    .first { it.feature == "unresolved_cards" }
                    .status shouldBe "carried"
            } finally {
                hydrated.bridge.teardownResources()
            }
        }

        test("attacker and committed blocker hydrate into Forge combat") {
            val attackerGrpId = TestCardRegistry.ensureCardRegistered("Raging Goblin")
            val blockerGrpId = TestCardRegistry.ensureCardRegistered("Grizzly Bears")
            val battlefieldZoneId = 7
            val attackerId = 201
            val blockerId = 101
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setTurnInfo(
                        TurnInfo
                            .newBuilder()
                            .setPhase(Phase.Combat_a549)
                            .setStep(Step.DeclareBlock_a2cb)
                            .setTurnNumber(4)
                            .setActivePlayer(2)
                            .setPriorityPlayer(1)
                            .setDecisionPlayer(1),
                    ).addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(3))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(2).setLifeTotal(20))
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(battlefieldZoneId)
                            .setType(ZoneType.Battlefield)
                            .addObjectInstanceIds(blockerId)
                            .addObjectInstanceIds(attackerId),
                    ).addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(blockerId)
                            .setGrpId(blockerGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(battlefieldZoneId)
                            .setOwnerSeatId(1)
                            .setControllerSeatId(1)
                            .setBlockState(BlockState.Blocking)
                            .setBlockInfo(BlockInfo.newBuilder().addAttackerIds(attackerId)),
                    ).addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(attackerId)
                            .setGrpId(attackerGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(battlefieldZoneId)
                            .setOwnerSeatId(2)
                            .setControllerSeatId(2)
                            .setAttackState(AttackState.Attacking),
                    ).build()

            val hydrated = SnapshotHydration.hydrate(gsm, 1, TestCardRegistry.repo)
            try {
                val game = hydrated.getGame().shouldNotBeNull()
                val combat = game.combat.shouldNotBeNull()
                val blocker =
                    game.players[0]
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .single { it.name == "Grizzly Bears" }
                val attacker =
                    game.players[1]
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .single { it.name == "Raging Goblin" }

                game.phaseHandler.phase.toString() shouldBe "COMBAT_DECLARE_BLOCKERS"
                combat.isAttacking(attacker) shouldBe true
                combat.isBlocking(blocker) shouldBe true
                combat.getAttackersBlockedBy(blocker).single() shouldBe attacker
                game.phaseHandler.priorityPlayer shouldBe game.players[0]
            } finally {
                hydrated.teardownResources()
            }
        }

        test("committed attacker phase is carried exactly") {
            val attackerGrpId = TestCardRegistry.ensureCardRegistered("Raging Goblin")
            val battlefieldZoneId = 7
            val attackerId = 201
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setTurnInfo(
                        TurnInfo
                            .newBuilder()
                            .setPhase(Phase.Combat_a549)
                            .setStep(Step.DeclareAttack_a2cb)
                            .setTurnNumber(4)
                            .setActivePlayer(1)
                            .setPriorityPlayer(1)
                            .setDecisionPlayer(1),
                    ).addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(20))
                    .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(2).setLifeTotal(20))
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(battlefieldZoneId)
                            .setType(ZoneType.Battlefield)
                            .setOwnerSeatId(1)
                            .addObjectInstanceIds(attackerId),
                    ).addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(attackerId)
                            .setGrpId(attackerGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(battlefieldZoneId)
                            .setOwnerSeatId(1)
                            .setControllerSeatId(1)
                            .setAttackState(AttackState.Attacking),
                    ).build()

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                hydrated.bridge
                    .getGame()
                    .shouldNotBeNull()
                    .phaseHandler.phase
                    .toString() shouldBe "COMBAT_DECLARE_ATTACKERS"
                val phase = hydrated.fidelity.features.single { it.feature == "phase" }
                phase.status shouldBe "carried"
                phase.detail shouldBe null
            } finally {
                hydrated.bridge.teardownResources()
            }
        }

        session(
            "hydrated game matches source on zones, flags, counters, damage, attachments, life, ids",
            puzzle =
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
                humanbattlefield=Mountain|Id:101|Tapped;Mountain|Id:102;Goblin Fireslinger|Id:103|SummonSick|Counters:P1P1=2;Pacifism|Id:104|AttachedTo:201
                humangraveyard=Shock
                aibattlefield=Grizzly Bears|Id:201|Damage:1
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val sourceBridge = bridge
            val sourceGame = sourceBridge.getGame().shouldNotBeNull()
            val snap = GsmSnapshot.capture(sourceGame, sourceBridge, "roundtrip", 0)
            val wireGsm =
                StateMapper
                    .buildFromSnapshot(snap, 0, "roundtrip", sourceBridge, viewingSeatId = 1)
                    .gsm
            val goblinIid =
                wireGsm.gameObjectsList
                    .first { TestCardRegistry.repo.findNameByGrpId(it.grpId) == "Goblin Fireslinger" }
                    .instanceId
            val gsm =
                wireGsm
                    .toBuilder()
                    .clearGameObjects()
                    .addAllGameObjects(
                        wireGsm.gameObjectsList.map { obj ->
                            if (obj.instanceId != goblinIid) {
                                obj
                            } else {
                                obj
                                    .toBuilder()
                                    .setPower(Int32Value.newBuilder().setValue(obj.power.value - 1))
                                    .setToughness(Int32Value.newBuilder().setValue(obj.toughness.value - 1))
                                    .build()
                            }
                        },
                    ).build()

            val hydratedSnapshot =
                SnapshotHydration.hydrateWithReport(
                    gsm = gsm,
                    consultSeat = 1,
                    cardRepository = TestCardRegistry.repo,
                )
            val hydrated = hydratedSnapshot.bridge
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
                goblin.netPower shouldBe 2
                goblin.netToughness shouldBe 2

                val pacifism =
                    hydratedGame.players[0].battlefield.card("Pacifism")
                val bear =
                    hydratedGame.players[1].battlefield.card("Grizzly Bears")
                bear.damage shouldBe 1
                pacifism.entityAttachedTo shouldBe bear

                hydratedSnapshot.fidelity.features.first { it.feature == "marked_damage" }.let {
                    it.status shouldBe "carried"
                    it.count shouldBe 1
                }
                hydratedSnapshot.fidelity.features.first { it.feature == "attachments" }.let {
                    it.status shouldBe "carried"
                    it.count shouldBe 1
                }
                hydratedSnapshot.fidelity.features
                    .first { it.feature == "characteristics" }
                    .status shouldBe "carried"

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
                    val forgeId = hydrated.getForgeCardId(InstanceId(obj.instanceId)).shouldNotBeNull()
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
