package leyline.protocol

import forge.game.card.Card
import forge.game.event.GameEventSpellAbilityCast
import forge.game.spellability.SpellAbilityStackInstance
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.bundle.LifecycleMessageMaterializer
import leyline.game.event.GameEvent
import leyline.game.mapping.ZoneIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

class PuzzleInitialMechanicSourceTest :
    BoardTest({
        test("event-bearing puzzle initial bundle preserves the cut source zone") {
            lateinit var source: Card
            val board =
                startWithBoard { _, human, _ ->
                    source = addCard("Soul Warden", human, ZoneType.Graveyard)
                }
            val ability = source.triggers.first().ensureAbility()
            ability.activatingPlayer = source.controller
            val collector = checkNotNull(board.bridge.eventCollector)
            collector.receiveGameEvent(
                GameEventSpellAbilityCast(
                    ability,
                    SpellAbilityStackInstance(ability),
                    0,
                ),
            )
            collector
                .peekEvents()
                .filterIsInstance<GameEvent.SpellCast>()
                .single()
                .isTrigger shouldBe true

            val (bundle, _) =
                LifecycleMessageMaterializer.puzzleInitialBundle(
                    seatId = SeatId(1),
                    matchId = Board.TEST_MATCH_ID,
                    msgIdStart = 1,
                    gameStateId = 1,
                    bridge = board.bridge,
                )
            val gsm =
                bundle.greToClientEvent.greToClientMessagesList
                    .single { it.type == GREMessageType.GameStateMessage_695e }
                    .gameStateMessage
            val created = gsm.annotationsList.single { AnnotationType.AbilityInstanceCreated in it.typeList }

            assertSoftly {
                created.detailInt("source_zone") shouldBe ZoneIds.P1_GRAVEYARD
                collector.peekEvents().shouldBeEmpty()
            }
        }
    })
