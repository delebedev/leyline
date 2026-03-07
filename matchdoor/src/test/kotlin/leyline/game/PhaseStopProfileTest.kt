package leyline.game

import forge.game.phase.PhaseType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.PhaseStopProfile

/**
 * Tests for [PhaseStopProfile] — stop set per player.
 *
 * Conformance group: PhaseType.<clinit> requires Localizer
 * (loaded by initializeCardDatabase).
 */
class PhaseStopProfileTest :
    FunSpec({

        tags(ConformanceTag)

        beforeSpec {
            leyline.bridge.GameBootstrap.initializeCardDatabase(quiet = true)
        }

        test("human defaults have correct phases") {
            val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
            val humanStops = profile.getEnabled(1)
            humanStops shouldBe setOf(
                PhaseType.MAIN1,
                PhaseType.COMBAT_DECLARE_ATTACKERS,
                PhaseType.COMBAT_DECLARE_BLOCKERS,
                PhaseType.MAIN2,
            )
        }

        test("AI defaults have correct phases") {
            val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
            val aiStops = profile.getEnabled(2)
            aiStops shouldBe setOf(
                PhaseType.COMBAT_BEGIN,
                PhaseType.COMBAT_DECLARE_ATTACKERS,
                PhaseType.COMBAT_DECLARE_BLOCKERS,
                PhaseType.END_OF_TURN,
            )
        }

        test("isEnabled returns correctly") {
            val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
            profile.isEnabled(1, PhaseType.MAIN1).shouldBeTrue()
            profile.isEnabled(1, PhaseType.UPKEEP).shouldBeFalse()
            profile.isEnabled(1, PhaseType.DRAW).shouldBeFalse()
            profile.isEnabled(1, PhaseType.COMBAT_DAMAGE).shouldBeFalse()
            profile.isEnabled(1, PhaseType.COMBAT_END).shouldBeFalse()
            profile.isEnabled(1, PhaseType.CLEANUP).shouldBeFalse()
        }

        test("setEnabled toggles stop") {
            val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
            profile.isEnabled(1, PhaseType.UPKEEP).shouldBeFalse()

            profile.setEnabled(1, PhaseType.UPKEEP, true)
            profile.isEnabled(1, PhaseType.UPKEEP).shouldBeTrue()

            profile.setEnabled(1, PhaseType.UPKEEP, false)
            profile.isEnabled(1, PhaseType.UPKEEP).shouldBeFalse()
        }

        test("setEnabled for new player creates entry") {
            val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
            profile.isEnabled(99, PhaseType.MAIN1).shouldBeFalse()

            profile.setEnabled(99, PhaseType.MAIN1, true)
            profile.isEnabled(99, PhaseType.MAIN1).shouldBeTrue()
        }

        test("empty profile has no stops") {
            val profile = PhaseStopProfile.empty()
            for (phase in PhaseStopProfile.CANONICAL_PHASES) {
                profile.isEnabled(1, phase).shouldBeFalse()
            }
        }

        test("two player defaults both get human stops") {
            val profile = PhaseStopProfile.createTwoPlayerDefaults(player1Id = 1, player2Id = 2)
            val expected = setOf(
                PhaseType.MAIN1,
                PhaseType.COMBAT_DECLARE_ATTACKERS,
                PhaseType.COMBAT_DECLARE_BLOCKERS,
                PhaseType.MAIN2,
            )
            profile.getEnabled(1) shouldBe expected
            profile.getEnabled(2) shouldBe expected
        }
    })
