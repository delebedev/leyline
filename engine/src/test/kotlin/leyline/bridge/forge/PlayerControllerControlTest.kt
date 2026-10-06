package leyline.bridge.forge

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.testkit.BoardTest

class PlayerControllerControlTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:Controlled player callbacks
            Goal:Win
            Turns:4
            Difficulty:Easy
            Description:Retain callback ownership through temporary player control.
            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanlibrary=Forest
            ailibrary=Mountain
            """.trimIndent()

        test("nested player control retains the human callback factory and each subject") {
            val board = startPuzzleAtMain1(puzzle)
            val human = board.human
            val ai = board.ai
            val humanLobby = human.originalLobbyPlayer
            ai.addController(10, human)
            val controlledAi = ai.controller.shouldBeInstanceOf<PlayerController>()
            controlledAi.player shouldBe ai
            controlledAi.lobbyPlayer shouldBe humanLobby

            human.addController(11, ai)
            val controlledHuman = human.controller.shouldBeInstanceOf<PlayerController>()
            controlledHuman.player shouldBe human
            controlledHuman.lobbyPlayer shouldBe humanLobby
        }

        test("timed control layers retire without replacing the base callbacks") {
            val board = startPuzzleAtMain1(puzzle)
            val human = board.human
            val ai = board.ai
            val humanController = human.controller
            val aiController = ai.controller
            ai.addController(10, human)
            val firstControl = ai.controller
            ai.addController(11, human)
            ai.removeController(11)
            ai.controller shouldBe firstControl
            ai.removeController(10)
            assertSoftly {
                ai.controller shouldBe aiController
                ai.controllingPlayer shouldBe null
                human.controller shouldBe humanController
                human.controllingPlayer shouldBe null
            }
        }
    })
