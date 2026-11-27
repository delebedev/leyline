package leyline.bridge.forge

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.MulliganPhase
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.game.state.GameBridge
import leyline.match.Match
import leyline.match.MatchState
import leyline.testkit.TestCardRegistry

class PlayerControllerColorChoiceTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("Commander with a wild-card color commander reaches mulligan with the deck's color chosen") {
            val spells = listOf("Opt", "Impulse", "Negate", "Cancel", "Unsummon", "Essence Scatter", "Make Disappear", "Befuddle")
            val deck = "[Commander]\n1 Faceless One\n1 Candlekeep Sage\n[Deck]\n90 Island\n" + spells.joinToString("\n") { "1 $it" }
            val opponent = "[Commander]\n1 Ayli, Eternal Pilgrim\n[Deck]\n99 Plains"
            TestCardRegistry.ensureDeckRegistered(deck)
            TestCardRegistry.ensureDeckRegistered(opponent)
            val bridge = GameBridge(cardRepository = TestCardRegistry.repo, engineSettings = EngineSettings(mulliganWaitMs = 2_000L))
            val match = Match("commander-wild-card-color", bridge)
            try {
                match.start(seed = 42L, deckList1 = deck, deckList2 = opponent, variant = "commander")
                val faceless =
                    bridge
                        .getPlayer(SeatId(1))
                        .shouldNotBeNull()
                        .commanders
                        .single { it.name == "Faceless One" }
                assertSoftly {
                    match.state shouldBe MatchState.RUNNING
                    bridge
                        .mulliganBridge(SeatId(1))
                        .pendingPrompt()
                        .shouldNotBeNull()
                        .phase shouldBe MulliganPhase.WaitingKeep
                    faceless.chosenColors.toList().shouldContainExactly("blue")
                }
            } finally {
                match.close()
            }
        }
    })
