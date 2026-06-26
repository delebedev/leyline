package leyline.board.puzzles

import forge.game.GameType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.mapping.StateMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTest
import leyline.testkit.beInCommandOf
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.gsm
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameVariant

class CommanderPuzzleTest :
    BoardTest({

        test("puzzle with commander applies Brawl variant and places commander in zone 26") {
            val (b, game, _) = startPuzzleAtMain1FromResource("puzzles/commander-visibility.pzl")

            game.rules.hasAppliedVariant(GameType.Brawl).shouldBeTrue()

            val human = humanPlayer(b)
            human.commanders.first().name shouldBe "Arabella, Abandoned Doll"
            "Arabella, Abandoned Doll" should beInCommandOf(human, count = 1)

            val snap = GsmSnapshot.capture(game, b, "test", 999)
            val gsm = StateMapper.buildFromSnapshot(snap, 999, "test", b, viewingSeatId = 1).gsm

            assertSoftly {
                gsm.gameInfo.hasDeckConstraintInfo().shouldBeTrue()
                gsm.gameInfo.variant shouldBe GameVariant.Brawl
                gsm.gameInfo.deckConstraintInfo.minCommanderSize shouldBe 1
            }

            val commandZone = gsm.zonesList.first { it.zoneId == ZoneIds.COMMAND }
            commandZone.objectInstanceIdsList.shouldNotBeEmpty()

            val commanderDesignations =
                gsm.persistentAnnotationsList.filter {
                    AnnotationType.Designation in it.typeList && it.detailInt("DesignationType") == 1
                }
            commanderDesignations.size shouldBe 2
            val playerDesignation = commanderDesignations.single { it.affectorId == 1 && it.affectedIdsList == listOf(1) }
            val objectDesignation = commanderDesignations.single { it.affectorId in commandZone.objectInstanceIdsList }

            assertSoftly {
                playerDesignation.detailInt("grpid") shouldBe objectDesignation.detailInt("grpid")
                playerDesignation.detailInt("CostIncrease") shouldBe 0
                objectDesignation.detailInt("CostIncrease") shouldBe 0
                playerDesignation.detailIntList("ColorIdentity") shouldBe listOf(1, 4)
                objectDesignation.detailIntList("ColorIdentity") shouldBe listOf(1, 4)
            }
        }

        test("commander tax appears in commander designation annotations") {
            val (b, game, _) = startPuzzleAtMain1FromResource("puzzles/commander-tax.pzl")

            val human = humanPlayer(b)
            human.getCommanderCast(human.commanders.first()) shouldBe 1

            val snap = GsmSnapshot.capture(game, b, "test", 999)
            val gsm = StateMapper.buildFromSnapshot(snap, 999, "test", b, viewingSeatId = 1).gsm
            val commandZone = gsm.zonesList.first { it.zoneId == ZoneIds.COMMAND }
            val commanderDesignations =
                gsm.persistentAnnotationsList.filter {
                    AnnotationType.Designation in it.typeList && it.detailInt("DesignationType") == 1
                }

            commanderDesignations.size shouldBe 2
            val playerDesignation = commanderDesignations.single { it.affectorId == 1 && it.affectedIdsList == listOf(1) }
            val objectDesignation = commanderDesignations.single { it.affectorId in commandZone.objectInstanceIdsList }

            assertSoftly {
                playerDesignation.detailInt("CostIncrease") shouldBe 2
                objectDesignation.detailInt("CostIncrease") shouldBe 2
            }
        }
    })
