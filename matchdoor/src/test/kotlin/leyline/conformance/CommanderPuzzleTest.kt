package leyline.conformance

import forge.game.GameType
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.SeatId
import leyline.game.mapper.ZoneIds

class CommanderPuzzleTest :
    FunSpec({

        tags(ConformanceTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("puzzle with commander applies Brawl variant and places commander in zone 26") {
            val h = MatchFlowHarness()
            harness = h
            h.connectAndKeepPuzzle("puzzles/commander-visibility.pzl")

            val game = h.game()
            val human = h.bridge.getPlayer(SeatId(1))!!

            // Brawl variant should be auto-applied
            game.rules.hasAppliedVariant(GameType.Brawl).shouldBeTrue()

            // Commander should be in the command zone
            val commanders = human.commanders
            commanders.shouldNotBeEmpty()
            commanders.first().name shouldBe "Arabella, Abandoned Doll"

            // Commander zone should have the card
            val commandZone = human.getZone(ZoneType.Command)
            val commanderCards = commandZone.cards.filter { it.name == "Arabella, Abandoned Doll" }
            commanderCards.size shouldBe 1

            // Accumulated GSM should include zone 26 with commander instanceId
            val zone26 = h.accumulator.zones[ZoneIds.COMMAND]
            checkNotNull(zone26) { "Zone 26 (Command) not in accumulated state" }
            zone26.objectInstanceIdsList.shouldNotBeEmpty()
        }
    })
