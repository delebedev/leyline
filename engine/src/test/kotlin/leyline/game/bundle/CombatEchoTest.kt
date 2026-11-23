package leyline.game.bundle

import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.*

class CombatEchoTest :
    BoardTest({
        test("combat echoes keep the printed-only object bytes") {
            val board = startPuzzleAtMain1(PUZZLE)
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
            val printed =
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
            objectInfo.toByteArray().toList() shouldBe printed.toByteArray().toList()
        }

        test("a combat echo omits the printed rows of a creature that lost all abilities") {
            val board = startPuzzleAtMain1(SLUDGE_PUZZLE)
            val (bridge, game, counter) = board
            board.gameStart()
            val iid =
                bridge
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            game.humanPlayer.battlefield
                                .card("Serra Angel")
                                .id,
                        ),
                    ).value
            val echo =
                board.bundleBuilder().prepareEchoAttackers(
                    game,
                    counter,
                    listOf(iid),
                    listOf(iid),
                    presentationActions = ActionsAvailableReq.getDefaultInstance(),
                )
            echo.bundle.messages
                .single { it.hasGameStateMessage() }
                .gameStateMessage.gameObjectsList
                .single()
                .uniqueAbilitiesCount shouldBe 0
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
    humanbattlefield=Grizzly Bears
    humanlibrary=Plains;Plains
    aibattlefield=Hill Giant
    ailibrary=Forest;Forest
"""

private const val SLUDGE_PUZZLE = """
    [metadata]
    Name:Combat Echo Ability Removal
    Goal:Demo
    Turns:3
    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=40
    AILife=40
    removesummoningsickness=true
    humanbattlefield=Sludge Monster;Serra Angel|Counters:SLIME=1
    humanlibrary=Plains;Plains
    aibattlefield=Hill Giant
    ailibrary=Forest;Forest
"""
