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
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
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
@Suppress("MissingAssertSoftly", "LargeClass") // Fidelity cases share one hydrated-state lifecycle fixture.
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
                battlefieldGsm()
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
                battlefieldGsm()
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

        test("stale attachment annotation does not make a retired aura unsafe") {
            val targetGrpId = TestCardRegistry.ensureCardRegistered("Grizzly Bears")
            val auraGrpId = TestCardRegistry.ensureCardRegistered("Pacifism")
            val gsm =
                battlefieldGsm()
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(8)
                            .setType(ZoneType.Graveyard)
                            .setOwnerSeatId(1),
                    ).addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(201)
                            .setGrpId(targetGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(7)
                            .setOwnerSeatId(2)
                            .setControllerSeatId(2),
                    ).addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(202)
                            .setGrpId(auraGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(8)
                            .setOwnerSeatId(1)
                            .setControllerSeatId(1),
                    ).addPersistentAnnotations(
                        AnnotationInfo
                            .newBuilder()
                            .setId(302)
                            .addType(AnnotationType.Attachment)
                            .setAffectorId(202)
                            .addAffectedIds(201),
                    ).build()

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                hydrated.fidelity.features
                    .single { it.feature == "attachments" }
                    .let { feature ->
                        feature.count shouldBe 0
                        feature.status shouldBe "carried"
                    }
                hydrated.bridge
                    .getGame()
                    .shouldNotBeNull()
                    .players[0]
                    .getZone(ForgeZoneType.Graveyard)
                    .cards
                    .single { it.name == "Pacifism" }
                    .entityAttachedTo shouldBe null
            } finally {
                hydrated.bridge.teardownResources()
            }
        }

        test("face-down card uses public characteristics without requiring its hidden identity") {
            val battlefieldZoneId = 7
            val faceDownIid = 201
            val gsm =
                battlefieldGsm(battlefieldZoneId)
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

        test("battlefield card keeps visible dynamic characteristics") {
            val grpId = TestCardRegistry.ensureCardRegistered("Impact Tremors")
            val swordGrpId = TestCardRegistry.ensureCardRegistered("Short Sword")
            val instanceId = 201
            val swordId = 202
            val battlefieldZoneId = 7
            val gsm =
                battlefieldGsm(battlefieldZoneId)
                    .addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(instanceId)
                            .setGrpId(grpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(battlefieldZoneId)
                            .setOwnerSeatId(1)
                            .setControllerSeatId(1)
                            .addCardTypes(CardType.Creature)
                            .addCardTypes(CardType.Enchantment)
                            .addSubtypes(SubType.Beast)
                            .setPower(Int32Value.newBuilder().setValue(7))
                            .setToughness(Int32Value.newBuilder().setValue(7)),
                    ).addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(swordId)
                            .setGrpId(swordGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(battlefieldZoneId)
                            .setOwnerSeatId(1)
                            .setControllerSeatId(1),
                    ).addPersistentAnnotations(
                        AnnotationInfo
                            .newBuilder()
                            .setId(302)
                            .addType(AnnotationType.Attachment)
                            .setAffectorId(swordId)
                            .addAffectedIds(instanceId),
                    ).build()

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                val card =
                    hydrated.bridge
                        .getGame()
                        .shouldNotBeNull()
                        .players[0]
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .single { it.name == "Impact Tremors" }
                val sword =
                    hydrated.bridge
                        .getGame()
                        .shouldNotBeNull()
                        .players[0]
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .single { it.name == "Short Sword" }

                card.type.isCreature shouldBe true
                card.type.isEnchantment shouldBe true
                card.type.hasSubtype("Beast") shouldBe true
                card.netPower shouldBe 7
                card.netToughness shouldBe 7
                sword.entityAttachedTo shouldBe card
                hydrated.fidelity.features
                    .first { it.feature == "characteristics" }
                    .status shouldBe "carried"
            } finally {
                hydrated.bridge.teardownResources()
            }
        }

        test("visible toughness is committed before marked damage settles") {
            val bearGrpId = TestCardRegistry.ensureCardRegistered("Grizzly Bears")
            val gsm =
                battlefieldGsm()
                    .addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(201)
                            .setGrpId(bearGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(7)
                            .setOwnerSeatId(1)
                            .setControllerSeatId(1)
                            .addCardTypes(CardType.Creature)
                            .setPower(Int32Value.newBuilder().setValue(5))
                            .setToughness(Int32Value.newBuilder().setValue(5))
                            .setDamage(4),
                    ).build()

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                val bear =
                    hydrated.bridge
                        .getGame()
                        .shouldNotBeNull()
                        .players[0]
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .single { it.name == "Grizzly Bears" }
                bear.netPower shouldBe 5
                bear.netToughness shouldBe 5
                bear.damage shouldBe 4
                hydrated.fidelity.features
                    .single { it.feature == "marked_damage" }
                    .status shouldBe "carried"
            } finally {
                hydrated.bridge.teardownResources()
            }
        }

        test("temporary indestructible is restored before lethal marked damage") {
            val bearGrpId = TestCardRegistry.ensureCardRegistered("Grizzly Bears")
            val gsm =
                battlefieldGsm()
                    .addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(201)
                            .setGrpId(bearGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(7)
                            .setOwnerSeatId(2)
                            .setControllerSeatId(2)
                            .addCardTypes(CardType.Creature)
                            .setPower(Int32Value.newBuilder().setValue(2))
                            .setToughness(Int32Value.newBuilder().setValue(2))
                            .setDamage(2),
                    ).addPersistentAnnotations(
                        AnnotationInfo
                            .newBuilder()
                            .setId(300)
                            .addType(AnnotationType.AddAbility_af5a)
                            .addAffectedIds(201)
                            .addDetails(
                                KeyValuePairInfo
                                    .newBuilder()
                                    .setKey("grpid")
                                    .setType(KeyValuePairValueType.Int32)
                                    .addValueInt32(104),
                            ),
                    ).build()

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                val bear =
                    hydrated.bridge
                        .getGame()
                        .shouldNotBeNull()
                        .players[1]
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .single { it.name == "Grizzly Bears" }
                bear.hasKeyword("Indestructible") shouldBe true
                bear.damage shouldBe 2
            } finally {
                hydrated.bridge.teardownResources()
            }
        }

        test("player attachment is restored") {
            val shadowGrpId = TestCardRegistry.ensureCardRegistered("Shadow of the Second Sun")
            val gsm =
                battlefieldGsm()
                    .addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(201)
                            .setGrpId(shadowGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(7)
                            .setOwnerSeatId(1)
                            .setControllerSeatId(1)
                            .addCardTypes(CardType.Enchantment)
                            .addSubtypes(SubType.Aura),
                    ).addPersistentAnnotations(
                        AnnotationInfo
                            .newBuilder()
                            .setId(300)
                            .addType(AnnotationType.Attachment)
                            .setAffectorId(201)
                            .addAffectedIds(1),
                    ).build()

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                val game = hydrated.bridge.getGame().shouldNotBeNull()
                val shadow =
                    game.players[0]
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .single()
                shadow.entityAttachedTo shouldBe game.players[0]
                hydrated.fidelity.features
                    .single { it.feature == "attachments" }
                    .status shouldBe "carried"
            } finally {
                hydrated.bridge.teardownResources()
            }
        }

        test("planeswalker toughness-like field is not treated as creature toughness") {
            val chandraGrpId = TestCardRegistry.ensureCardRegistered("Chandra, Torch of Defiance")
            val gsm =
                battlefieldGsm()
                    .addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(201)
                            .setGrpId(chandraGrpId)
                            .setType(GameObjectType.Card)
                            .setZoneId(7)
                            .setOwnerSeatId(1)
                            .setControllerSeatId(1)
                            .addCardTypes(CardType.Planeswalker)
                            .setToughness(Int32Value.newBuilder().setValue(4)),
                    ).addPersistentAnnotations(
                        AnnotationInfo
                            .newBuilder()
                            .setId(301)
                            .addType(AnnotationType.Counter_803b)
                            .addAffectedIds(201)
                            .addDetails(
                                KeyValuePairInfo
                                    .newBuilder()
                                    .setKey("count")
                                    .setType(KeyValuePairValueType.Int32)
                                    .addValueInt32(4),
                            ).addDetails(
                                KeyValuePairInfo
                                    .newBuilder()
                                    .setKey("counter_type")
                                    .setType(KeyValuePairValueType.Int32)
                                    .addValueInt32(7),
                            ),
                    ).build()

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                hydrated.bridge
                    .getGame()
                    .shouldNotBeNull()
                    .players[0]
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .single()
                    .type.isPlaneswalker shouldBe true
                hydrated.fidelity.features
                    .single { it.feature == "characteristics" }
                    .status shouldBe "carried"
            } finally {
                hydrated.bridge.teardownResources()
            }
        }

        test("copied Room token is explicitly unavailable") {
            TestCardRegistry.repo.register(990_003, "Restricted Office // Lecture Hall")
            val gsm =
                battlefieldGsm()
                    .addGameObjects(
                        GameObjectInfo
                            .newBuilder()
                            .setInstanceId(201)
                            .setGrpId(990_003)
                            .setType(GameObjectType.Token)
                            .setZoneId(7)
                            .setOwnerSeatId(2)
                            .setControllerSeatId(2)
                            .setIsCopy(true)
                            .addCardTypes(CardType.Enchantment)
                            .addSubtypes(SubType.Room),
                    ).build()

            val hydrated = SnapshotHydration.hydrateWithReport(gsm, 1, TestCardRegistry.repo)
            try {
                hydrated.fidelity.features.single { it.feature == "unresolved_cards" }.let {
                    it.status shouldBe "missing"
                    it.instanceIds shouldBe listOf(201)
                }
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

private fun battlefieldGsm(zoneId: Int = 7): GameStateMessage.Builder =
    GameStateMessage
        .newBuilder()
        .setTurnInfo(TurnInfo.newBuilder().setActivePlayer(1).setTurnNumber(3))
        .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(20))
        .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(2).setLifeTotal(20))
        .addZones(ZoneInfo.newBuilder().setZoneId(zoneId).setType(ZoneType.Battlefield))
