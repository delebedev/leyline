package leyline.board.puzzles

import forge.game.GameType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTest
import leyline.testkit.beInCommandOf
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.gsm
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameVariant
import leyline.testkit.StateMapperShell as StateMapper

private val COMMANDER_VISIBILITY_PUZZLE =
    """
    [metadata]
    Name:Commander Visibility Test
    Goal:Verify commander card renders in command zone
    Turns:3
    Difficulty:Tutorial
    Description:Arabella in command zone should be visible to the client as a clickable card.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=25
    AILife=25

    humancommand=Arabella, Abandoned Doll|IsCommander
    humanhand=Plains;Plains;Plains
    humanbattlefield=Plains;Plains
    humanlibrary=Plains;Plains;Plains;Plains;Plains
    aibattlefield=Mountain;Mountain
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

private val COMMANDER_TAX_PUZZLE =
    """
    [metadata]
    Name:Commander Tax Test
    Goal:Verify commander tax is visible in command zone annotations
    Turns:3
    Difficulty:Tutorial
    Description:Arabella has already been cast once and should carry one commander-tax step.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=25
    AILife=25

    humancommand=Arabella, Abandoned Doll|IsCommander|CommanderCast:1
    humanhand=Plains;Plains;Plains
    humanbattlefield=Plains;Plains;Mountain;Mountain
    humanlibrary=Plains;Plains;Plains;Plains;Plains
    aibattlefield=Mountain;Mountain
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class CommanderPuzzleTest :
    BoardTest({

        test("puzzle with commander applies Brawl variant and places commander in zone 26") {
            val board = startPuzzleAtMain1(COMMANDER_VISIBILITY_PUZZLE)

            board.game.rules
                .hasAppliedVariant(GameType.Brawl)
                .shouldBeTrue()

            val human = board.human
            human.commanders.first().name shouldBe "Arabella, Abandoned Doll"
            "Arabella, Abandoned Doll" should beInCommandOf(human, count = 1)

            val snap = GsmSnapshot.capture(board.game, board.bridge, "test", 999)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        999,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

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
            val board = startPuzzleAtMain1(COMMANDER_TAX_PUZZLE)

            val human = board.human
            human.getCommanderCast(human.commanders.first()) shouldBe 1

            val snap = GsmSnapshot.capture(board.game, board.bridge, "test", 999)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snap,
                        999,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
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
