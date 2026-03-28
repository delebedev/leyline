package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag

class ConcealingCurtainsPuzzleTest : FunSpec({

    tags(ConformanceTag)

    val base = ConformanceTestBase()
    beforeSpec { base.initCardDatabase() }
    afterEach { base.tearDown() }

    test("concealing curtains transform puzzle loads and starts") {
        val pzl = """
            [metadata]
            Name:Concealing Curtains Transform
            Goal:Win
            Turns:4
            Difficulty:Easy
            Description:Transform Concealing Curtains into Revealing Eye and attack for lethal.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=3

            humanbattlefield=Concealing Curtains;Swamp;Swamp;Swamp
            humanlibrary=Swamp
            aibattlefield=
            aihand=Grizzly Bears
            ailibrary=Forest
        """.trimIndent()
        val (b, game, _) = base.startPuzzleAtMain1(pzl)
        val bf = game.humanPlayer.getZone(forge.game.zone.ZoneType.Battlefield).cards
        bf.any { it.name == "Concealing Curtains" } shouldBe true
        bf.count { it.isLand } shouldBe 3
    }
})
