package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.*
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/** Shock lands expose the optional life payment through the semantic action seam. */
class ShockLandEtbTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Temple Garden")
            TestCardRegistry.ensureCardRegistered("Forest")
            TestCardRegistry.ensureCardRegistered("Mountain")
        }

        fun puzzleText() =
            """
            [metadata]
            Name:Shock Land ETB
            Goal:Win
            Turns:1
            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Temple Garden
            humanlibrary=Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        session("accept — pay 2 life, land enters untapped", puzzle = puzzleText()) {
            holdNextOptionalAction()
            playLand("Temple Garden") shouldBe true
            allMessages.any { it.type == GREMessageType.OptionalActionMessage_695e } shouldBe true
            respondToOptionalAction(true)
            human.life shouldBe 18
            val templeGarden = human.battlefield.card("Temple Garden")
            templeGarden.isTapped shouldBe false
        }

        session("decline — land enters tapped, life unchanged", puzzle = puzzleText()) {
            holdNextOptionalAction()
            playLand("Temple Garden") shouldBe true
            allMessages.any { it.type == GREMessageType.OptionalActionMessage_695e } shouldBe true
            respondToOptionalAction(false)
            human.life shouldBe 20
            human.getZone(ZoneType.Battlefield).card("Temple Garden").isTapped shouldBe true
        }
    })
