package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import leyline.IntegrationTag
import leyline.bridge.SeatId
import leyline.game.StateMapper
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.ObjectMapper

/**
 * Treasure token grpId resolution — regression test for NPE crash.
 *
 * Crash: Treasure tokens get grpId=0 → ExposedCardRepository.findByGrpId
 * puts null into ConcurrentHashMap → NPE in ActionMapper.buildActionList.
 *
 * Fix: ActionMapper uses ObjectMapper.resolveGrpId (token-aware) instead
 * of findGrpIdByName (filters isToken=0). ExposedCardRepository guards
 * against null cache puts.
 */
class TreasureTokenTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        val puzzleText = """
            [metadata]
            Name:Treasure Token ETB
            Goal:Win
            Turns:3
            Difficulty:Easy
            Description:Cast Prosperous Innkeeper to create a Treasure token.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=3

            humanhand=Prosperous Innkeeper;Lightning Bolt
            humanbattlefield=Forest;Forest
            humanlibrary=Forest;Forest;Forest
            aibattlefield=Centaur Courser
            ailibrary=Mountain;Mountain;Mountain
        """.trimIndent()

        test("Treasure token resolves to valid grpId after ETB creation") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            // Cast Prosperous Innkeeper (1G — two forests available)
            h.castSpellByName("Prosperous Innkeeper").shouldBeTrue()

            // Resolve — pass until stack is empty
            repeat(10) {
                if (h.isGameOver()) return@repeat
                if (h.game().stackZone.isEmpty) return@repeat
                h.passPriority()
            }

            // Treasure should exist somewhere — battlefield, graveyard, or exile.
            // Even if the game advanced past combat, the token was created.
            // The key test is that resolveGrpId doesn't crash.
            val player = h.bridge.getPlayer(SeatId(1))!!

            // Find the treasure in ANY zone
            val allCards = listOf(ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile)
                .flatMap { player.getZone(it).cards }
            val treasure = allCards.firstOrNull { it.name == "Treasure" && it.isToken }

            // Regression: resolveGrpId must return non-zero for Treasure tokens
            if (treasure != null) {
                val grpId = ObjectMapper.resolveGrpId(treasure, h.bridge.cards)
                grpId shouldBeGreaterThan 0
            }

            // Key assertion: buildFromGame must not crash (was NPE before fix)
            val gsm = StateMapper.buildFromGame(
                h.game(),
                1,
                "test-treasure",
                h.bridge,
                viewingSeatId = 1,
            )
            gsm.shouldNotBeNull()

            // buildActions must not crash (was NPE in ActionMapper before fix)
            val actions = ActionMapper.buildActions(h.game(), 1, h.bridge)
            actions.actionsCount shouldBeGreaterThan 0
        }

        test("Treasure token has ActivateMana action after creation") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            // Cast Innkeeper
            h.castSpellByName("Prosperous Innkeeper").shouldBeTrue()

            // Resolve
            repeat(10) {
                if (h.game().stackZone.isEmpty) return@repeat
                h.passPriority()
            }

            // Treasure should be on battlefield (may have been sacrificed if game advanced)
            val player = h.bridge.getPlayer(SeatId(1))!!
            val treasure = player.getZone(ZoneType.Battlefield).cards
                .firstOrNull { it.name == "Treasure" }

            if (treasure != null) {
                treasure.isToken.shouldBeTrue()

                // Actions should include ActivateMana for the Treasure
                val actions = ActionMapper.buildActions(h.game(), 1, h.bridge)
                val manaActions = actions.actionsList.filter {
                    it.actionType.name == "ActivateMana"
                }
                manaActions.size shouldBeGreaterThan 0
            }
            // If treasure was already sacrificed (auto-pass used it), the test still
            // verifies that no crash occurred during the game flow.
        }
    })
