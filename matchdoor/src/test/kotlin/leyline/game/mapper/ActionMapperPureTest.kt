package leyline.game.mapper

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Pure tests for [ActionMapper.buildActionList] — the overload with function params.
 *
 * Uses [ConformanceTestBase.startWithBoard] to set up board state without a full
 * game loop. The key point: [ActionMapper.buildActionList] itself holds no
 * [leyline.game.GameBridge] reference — the bridge only provides the lambdas.
 */
class ActionMapperPureTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // -----------------------------------------------------------------------
        // Test 1: Pass action always present
        // -----------------------------------------------------------------------

        test("buildActionList includes Pass action on empty board") {
            val (b, game, _) = base.startWithBoard { _, _, _ -> }
            val human = game.humanPlayer

            val actions = ActionMapper.buildActionList(
                player = human,
                seatId = 1,
                checkLegality = false,
                idResolver = { forgeCardId -> b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value },
                grpIdResolver = { card -> ObjectMapper.resolveGrpId(card, b.cards) },
                cardDataLookup = { grpId -> b.cards.findByGrpId(grpId) },
            )

            val hasPass = actions.actionsList.any { it.actionType == ActionType.Pass }
            hasPass.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 2: Land in hand → inactiveActions (naive mode: no canPlayLand check)
        // -----------------------------------------------------------------------

        test("buildActionList includes Play for lands in hand (inactiveActions in naive mode)") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Island", human, ZoneType.Hand)
            }
            val human = game.humanPlayer

            val actions = ActionMapper.buildActionList(
                player = human,
                seatId = 1,
                checkLegality = false,
                idResolver = { forgeCardId -> b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value },
                grpIdResolver = { card -> ObjectMapper.resolveGrpId(card, b.cards) },
                cardDataLookup = { grpId -> b.cards.findByGrpId(grpId) },
            )

            // In naive mode lands are always non-playable → inactiveActions
            val hasPlay = actions.inactiveActionsList.any { it.actionType == ActionType.Play_add3 }
            hasPlay.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 3: Non-land spell in hand → Cast in actions
        // -----------------------------------------------------------------------

        test("buildActionList includes Cast for non-land spells in hand") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Llanowar Elves", human, ZoneType.Hand)
            }
            val human = game.humanPlayer

            val actions = ActionMapper.buildActionList(
                player = human,
                seatId = 1,
                checkLegality = false,
                idResolver = { forgeCardId -> b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value },
                grpIdResolver = { card -> ObjectMapper.resolveGrpId(card, b.cards) },
                cardDataLookup = { grpId -> b.cards.findByGrpId(grpId) },
            )

            val hasCast = actions.actionsList.any { it.actionType == ActionType.Cast }
            hasCast.shouldBeTrue()
        }

        // -----------------------------------------------------------------------
        // Test 4: Untapped land on battlefield → ActivateMana in actions
        // -----------------------------------------------------------------------

        test("buildActionList includes ActivateMana for untapped lands on battlefield") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Island", human, ZoneType.Battlefield)
            }
            val human = game.humanPlayer

            val actions = ActionMapper.buildActionList(
                player = human,
                seatId = 1,
                checkLegality = false,
                idResolver = { forgeCardId -> b.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value },
                grpIdResolver = { card -> ObjectMapper.resolveGrpId(card, b.cards) },
                cardDataLookup = { grpId -> b.cards.findByGrpId(grpId) },
            )

            val hasActivateMana = actions.actionsList.any { it.actionType == ActionType.ActivateMana }
            hasActivateMana.shouldBeTrue()
        }
    })
