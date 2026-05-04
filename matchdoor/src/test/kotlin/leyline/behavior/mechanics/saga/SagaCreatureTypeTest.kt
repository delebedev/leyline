package leyline.behavior.mechanics.saga

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import leyline.IntegrationTag
import leyline.testkit.MatchFlowHarness
import leyline.testkit.TestCardRegistry
import leyline.testkit.assertConsistent
import leyline.testkit.humanPlayer

/**
 * Phase 4 — creature-saga (scope §2d).
 *
 * Summon: Brynhildr is Enchantment Creature Saga Knight, 2/1, {1}{R}. Cheapest
 * creature-saga available in the Forge corpus. Exercises the
 * [ObjectMapper.overlayCardTypes] path for a card carrying BOTH Enchantment
 * and Creature types simultaneously — the Arena client expects the creature
 * type to be live so it can render P/T + enforce summoning-sickness rules.
 *
 * Minimal assertion: after cast, the saga is on the battlefield with
 * Forge's type view exposing both Enchantment and Creature. Lifecycle
 * mechanics (Ch I/II/III resolution) are already covered by
 * SagaTransformPuzzleTest and SagaTargetedChapterTest; this test is the
 * creature-type-overlay regression gate.
 */
class SagaCreatureTypeTest :
    FunSpec({

        tags(IntegrationTag)

        test("summon: brynhildr casts with both Enchantment and Creature types live") {
            val puzzleText =
                """
                [metadata]
                Name:Creature-Saga Type Overlay — Summon: Brynhildr
                Goal:Survive
                Turns:4
                Difficulty:Easy
                Description:Cast Summon: Brynhildr, assert it's both Enchantment and Creature on the battlefield.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Summon: Brynhildr
                humanbattlefield=Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                aibattlefield=Forest
                ailibrary=Forest;Forest;Forest
                """.trimIndent()

            val harness = MatchFlowHarness(validating = false)
            try {
                harness.connectAndKeepPuzzleText(puzzleText)
                val game = harness.bridge.getGame()!!

                harness.castSpellByName("Summon: Brynhildr").shouldBeTrue()
                // Let the cast resolve onto the battlefield.
                harness.passPriority()
                harness.passPriority()

                val saga =
                    game.humanPlayer
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .firstOrNull { it.name == "Summon: Brynhildr" }
                saga.shouldNotBeNull()

                // Core assertion: live Forge card type view carries BOTH types.
                val types =
                    saga!!
                        .type.coreTypes
                        .map { it.name }
                        .toSet()
                types shouldContain "Enchantment"
                types shouldContain "Creature"

                // Sanity: creature stats are live (printed 2/1).
                saga.isCreature.shouldBeTrue()
                saga.currentPower shouldBeGreaterThanOrEqualTo 2
                saga.currentToughness shouldBeGreaterThanOrEqualTo 1

                // Client-accumulator assertion: the BF gameObject for the
                // saga carries both Enchantment and Creature card types live.
                harness.accumulator.assertConsistent("after creature-saga cast")
                val sagaAccObj =
                    harness.accumulator.objects.values
                        .firstOrNull {
                            it.type == wotc.mtgo.gre.external.messaging.Messages.GameObjectType.Card &&
                                it.grpId == TestCardRegistry.repo.findGrpIdByName("Summon: Brynhildr")
                        }
                sagaAccObj.shouldNotBeNull()
                val accTypes = sagaAccObj!!.cardTypesList.map { it.name }
                (accTypes.any { it.startsWith("Enchantment") }).shouldBeTrue()
                (accTypes.any { it == "Creature" }).shouldBeTrue()
            } finally {
                harness.shutdown()
            }
        }
    })
