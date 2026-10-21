package leyline.game.bundle

import forge.game.combat.Combat
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.KeywordProjection
import leyline.game.state.ProjectionTransition
import leyline.game.state.ViewerProjectionCursor
import leyline.testkit.BoardTest
import leyline.testkit.aiPlayer
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.*

class CombatEchoTest :
    BoardTest({
        test("committed echo qualification omits stale or missing prior identity mappings") {
            val board = startPuzzleAtMain1(PUZZLE, EngineSettings(keywordProjection = KeywordProjection.WebEffectiveFlying))
            val (bridge, game, counter) = board
            board.gameStart()
            val fid =
                ForgeCardId(
                    game.humanPlayer.battlefield
                        .card("Grizzly Bears")
                        .id,
                )
            val iid = bridge.getOrAllocInstanceId(fid).value
            val baseline = bridge.projectionStateSnapshot()
            val seat = SeatId(1)
            val cursor = baseline.viewerCursors.getValue(seat)
            val source = cursor.fullState!!.gameObjectsList.single { it.instanceId == iid }
            val paired = source.toBuilder().addAllAbilityOriginalCardGrpIds(List(source.uniqueAbilitiesCount) { source.grpId }).build()
            val committed =
                cursor.copy(
                    fullState =
                        cursor.fullState
                            .toBuilder()
                            .clearGameObjects()
                            .addGameObjects(paired)
                            .build(),
                )
            val identities = baseline.identities
            val variants =
                listOf(
                    identities.copy(forgeIdToInstanceId = identities.forgeIdToInstanceId - fid),
                    identities.copy(forgeIdToInstanceId = identities.forgeIdToInstanceId + (fid to InstanceId(iid + 999))),
                    identities.copy(instanceIdToForgeId = identities.instanceIdToForgeId - InstanceId(iid)),
                    identities.copy(
                        instanceIdToForgeId = identities.instanceIdToForgeId + (InstanceId(iid) to ForgeCardId(fid.value + 999)),
                    ),
                )
            for (identity in variants) {
                val prior = bridge.projectionStateSnapshot()
                bridge.commitProjection(
                    ProjectionTransition(
                        prior.revision,
                        prior.copy(
                            revision = prior.revision + 1,
                            identities = identity,
                            viewerCursors = prior.viewerCursors + (seat to committed),
                        ),
                    ),
                )
                val echo =
                    board.bundleBuilder().prepareEchoAttackers(
                        game,
                        counter,
                        emptyList(),
                        listOf(iid),
                        presentationActions = ActionsAvailableReq.getDefaultInstance(),
                    )
                assertSoftly {
                    echo.bundle.messages
                        .single { it.hasGameStateMessage() }
                        .gameStateMessage.gameObjectsCount shouldBe 0
                    echo.bundle.messages.count { it.hasDeclareAttackersReq() } shouldBe 1
                }
            }
        }

        test("omitted and explicit Native combat echoes retain the legacy printed-only object bytes") {
            val bytes = mutableListOf<List<Byte>>()
            for (settings in listOf(EngineSettings(), EngineSettings(keywordProjection = KeywordProjection.Native))) {
                val board = startPuzzleAtMain1(PUZZLE, settings)
                val (bridge, game, counter) = board
                board.gameStart()
                val iid =
                    bridge
                        .getOrAllocInstanceId(
                            ForgeCardId(
                                game.humanPlayer.battlefield
                                    .card("Grizzly Bears")
                                    .id,
                            ),
                        ).value
                val echo =
                    board.bundleBuilder().prepareEchoAttackers(
                        game,
                        counter,
                        emptyList(),
                        listOf(iid),
                        presentationActions = ActionsAvailableReq.getDefaultInstance(),
                    )
                val objectInfo =
                    echo.bundle.messages
                        .single { it.hasGameStateMessage() }
                        .gameStateMessage.gameObjectsList
                        .single()
                val legacy =
                    bridge.cardProto
                        .buildObjectInfo(objectInfo.grpId)
                        .setInstanceId(iid)
                        .setType(GameObjectType.Card)
                        .setZoneId(leyline.game.mapping.ZoneIds.BATTLEFIELD)
                        .setVisibility(Visibility.Public)
                        .setOwnerSeatId(1)
                        .setControllerSeatId(1)
                        .setOthersideGrpId(objectInfo.othersideGrpId)
                        .setPower(objectInfo.power)
                        .setToughness(objectInfo.toughness)
                        .setIsTapped(false)
                        .setHasSummoningSickness(false)
                        .build()
                objectInfo.toByteArray().toList() shouldBe legacy.toByteArray().toList()
                bytes += objectInfo.toByteArray().toList()
            }
            bytes[0] shouldBe bytes[1]
        }

        test("granted Flying and paired rows survive attacker and blocker select deselect and repeated echoes") {
            val board = startPuzzleAtMain1(PUZZLE, EngineSettings(keywordProjection = KeywordProjection.WebEffectiveFlying))
            val (bridge, game, counter) = board
            board.gameStart()
            val bear = game.humanPlayer.battlefield.card("Grizzly Bears")
            val iid = bridge.getOrAllocInstanceId(ForgeCardId(bear.id)).value
            val seat = SeatId(1)
            val prior = bridge.projectionStateSnapshot()
            val cursor = prior.viewerCursors.getValue(seat)
            val original = cursor.fullState!!.gameObjectsList.single { it.instanceId == iid }
            original.uniqueAbilitiesList.any { it.grpId == 8 } shouldBe true
            // A committed paired source preserves its exact recipient IDs and source associations.
            val source =
                original
                    .toBuilder()
                    .addAllAbilityOriginalCardGrpIds(
                        List(original.uniqueAbilitiesCount) { original.grpId },
                    ).build()
            bridge.commitProjection(
                ProjectionTransition(
                    prior.revision,
                    prior.copy(
                        revision = prior.revision + 1,
                        viewerCursors =
                            prior.viewerCursors +
                                (
                                    seat to
                                        cursor.copy(
                                            fullState =
                                                cursor.fullState
                                                    .toBuilder()
                                                    .clearGameObjects()
                                                    .addGameObjects(source)
                                                    .build(),
                                        )
                                ),
                    ),
                ),
            )
            val builder = board.bundleBuilder()
            val recipient =
                DamageRecipient
                    .newBuilder()
                    .setType(DamageRecType.Player_a0e5)
                    .setPlayerSystemSeatId(2)
                    .build()
            val attacker = game.aiPlayer.battlefield.card("Hill Giant")
            val attackerId = bridge.getOrAllocInstanceId(ForgeCardId(attacker.id)).value
            val combat = Combat(game.aiPlayer)
            combat.addAttacker(attacker, game.humanPlayer)
            game.phaseHandler.setCombat(combat)
            for (selected in listOf(true, false, true, false)) {
                val attack =
                    builder.prepareEchoAttackers(
                        game,
                        counter,
                        if (selected) listOf(iid) else emptyList(),
                        listOf(iid),
                        selectedDamageRecipients =
                            mapOf(
                                iid to recipient,
                            ),
                        presentationActions = ActionsAvailableReq.getDefaultInstance(),
                    )
                val block =
                    builder.prepareEchoBlockers(
                        game,
                        counter,
                        if (selected) mapOf(iid to attackerId) else emptyMap(),
                        listOf(iid),
                        ActionsAvailableReq.getDefaultInstance(),
                    )
                for (prepared in listOf(attack, block)) {
                    val echo =
                        prepared.bundle.messages
                            .single { it.hasGameStateMessage() }
                            .gameStateMessage.gameObjectsList
                            .single()
                    assertSoftly {
                        echo.uniqueAbilitiesList shouldBe source.uniqueAbilitiesList
                        echo.abilityOriginalCardGrpIdsList shouldBe source.abilityOriginalCardGrpIdsList
                    }
                }
                block.bundle.messages
                    .single { it.hasDeclareBlockersReq() }
                    .declareBlockersReq.blockersList
                    .single {
                        it.blockerInstanceId ==
                            iid
                    }.selectedAttackerInstanceIdsList shouldBe
                    if (selected) listOf(attackerId) else emptyList()
                attack.transition?.let(bridge::commitProjection)
            }
        }

        test("unqualified cursor identities and partial rows omit the object rather than claim empty membership") {
            val board = startPuzzleAtMain1(PUZZLE, EngineSettings(keywordProjection = KeywordProjection.WebEffectiveFlying))
            val (bridge, game, counter) = board
            board.gameStart()
            val iid =
                bridge
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer.battlefield
                                .card("Grizzly Bears")
                                .id,
                        ),
                    ).value
            val seat = SeatId(1)
            val original = bridge.projectionStateSnapshot().viewerCursors.getValue(seat)
            val source = original.fullState!!.gameObjectsList.single { it.instanceId == iid }
            val paired = source.toBuilder().addAllAbilityOriginalCardGrpIds(List(source.uniqueAbilitiesCount) { source.grpId }).build()
            val variants =
                listOf(
                    ViewerProjectionCursor(),
                    original.copy(
                        fullState =
                            original.fullState
                                .toBuilder()
                                .clearGameObjects()
                                .build(),
                    ),
                ) +
                    listOf(
                        paired.toBuilder().setInstanceId(iid + 999).build(),
                        paired.toBuilder().setGrpId(paired.grpId + 1).build(),
                        paired.toBuilder().setVisibility(Visibility.Private).build(),
                        paired.toBuilder().setType(GameObjectType.RevealedCard).build(),
                        paired.toBuilder().setIsFacedown(true).build(),
                        paired.toBuilder().addAbilityOriginalCardGrpIds(999).build(),
                        source,
                    ).map {
                        original.copy(
                            fullState =
                                original.fullState
                                    .toBuilder()
                                    .clearGameObjects()
                                    .addGameObjects(it)
                                    .build(),
                        )
                    }
            for (cursor in variants) {
                val prior = bridge.projectionStateSnapshot()
                bridge.commitProjection(
                    ProjectionTransition(
                        prior.revision,
                        prior.copy(
                            revision = prior.revision + 1,
                            viewerCursors =
                                prior.viewerCursors + (seat to cursor),
                        ),
                    ),
                )
                val echo =
                    board.bundleBuilder().prepareEchoAttackers(
                        game,
                        counter,
                        listOf(iid),
                        listOf(iid),
                        presentationActions = ActionsAvailableReq.getDefaultInstance(),
                    )
                assertSoftly {
                    echo.bundle.messages
                        .single { it.hasGameStateMessage() }
                        .gameStateMessage.gameObjectsCount shouldBe 0
                    echo.bundle.messages
                        .single { it.hasDeclareAttackersReq() }
                        .declareAttackersReq.attackersList
                        .single {
                            it.attackerInstanceId ==
                                iid
                        }.hasSelectedDamageRecipient() shouldBe
                        true
                }
            }
        }

        test("matching explicit known empty membership remains empty in the combat echo") {
            val board = startPuzzleAtMain1(PUZZLE, EngineSettings(keywordProjection = KeywordProjection.WebEffectiveFlying))
            val (bridge, game, counter) = board
            board.gameStart()
            val seat = SeatId(1)
            val iid =
                bridge
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer.battlefield
                                .card("Grizzly Bears")
                                .id,
                        ),
                    ).value
            val prior = bridge.projectionStateSnapshot()
            val cursor = prior.viewerCursors.getValue(seat)
            val source =
                cursor.fullState!!
                    .gameObjectsList
                    .single {
                        it.instanceId == iid
                    }.toBuilder()
                    .clearUniqueAbilities()
                    .clearAbilityOriginalCardGrpIds()
                    .build()
            bridge.commitProjection(
                ProjectionTransition(
                    prior.revision,
                    prior.copy(
                        revision = prior.revision + 1,
                        viewerCursors =
                            prior.viewerCursors +
                                (
                                    seat to
                                        cursor.copy(
                                            fullState =
                                                cursor.fullState
                                                    .toBuilder()
                                                    .clearGameObjects()
                                                    .addGameObjects(source)
                                                    .build(),
                                        )
                                ),
                    ),
                ),
            )
            val echo =
                board.bundleBuilder().prepareEchoAttackers(
                    game,
                    counter,
                    emptyList(),
                    listOf(iid),
                    presentationActions = ActionsAvailableReq.getDefaultInstance(),
                )
            echo.bundle.messages
                .single { it.hasGameStateMessage() }
                .gameStateMessage.gameObjectsList
                .single()
                .uniqueAbilitiesCount shouldBe
                0
        }
    })

private const val PUZZLE = """
    [metadata]
    Name:Combat Echo Membership
    Goal:Demo
    Turns:3
    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=40
    AILife=40
    removesummoningsickness=true
    humanbattlefield=Archetype of Imagination;Grizzly Bears
    humanlibrary=Plains;Plains
    aibattlefield=Hill Giant
    ailibrary=Forest;Forest
"""
