package leyline.conformance

import forge.game.GameType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.game.mapper.StateMapper
import leyline.game.mapper.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import wotc.mtgo.gre.external.messaging.Messages.GameVariant

class CommanderPuzzleTest :
    SubsystemTest({

        test("puzzle with commander applies Brawl variant and places commander in zone 26") {
            val (b, game, _) = startPuzzleAtMain1FromResource("puzzles/commander-visibility.pzl")

            game.rules.hasAppliedVariant(GameType.Brawl).shouldBeTrue()

            val human = humanPlayer(b)
            human.commanders.first().name shouldBe "Arabella, Abandoned Doll"
            human.getZone(ZoneType.Command).cards.count { it.name == "Arabella, Abandoned Doll" } shouldBe 1

            val snap = GsmSnapshot.capture(game, b, "test", 999)
            val gsm = StateMapper.buildFromSnapshot(snap, 999, "test", b, viewingSeatId = 1).gsm

            gsm.gameInfo.hasDeckConstraintInfo().shouldBeTrue()
            assertSoftly {
                gsm.gameInfo.variant shouldBe GameVariant.Brawl
                gsm.gameInfo.deckConstraintInfo.minCommanderSize shouldBe 1
            }

            val commandZone = gsm.zonesList.first { it.zoneId == ZoneIds.COMMAND }
            commandZone.objectInstanceIdsList.shouldNotBeEmpty()
        }
    })
