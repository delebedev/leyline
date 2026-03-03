package leyline.unit

import forge.game.phase.PhaseType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.PhaseStopProfile

class PhaseStopProfileTest :
    FunSpec({

        tags(UnitTag)

        test("createDefaults — human has own-turn stops, AI has combat stops") {
            val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)

            // Human own-turn defaults
            profile.isEnabled(1, PhaseType.MAIN1) shouldBe true
            profile.isEnabled(1, PhaseType.COMBAT_DECLARE_ATTACKERS) shouldBe true
            profile.isEnabled(1, PhaseType.COMBAT_DECLARE_BLOCKERS) shouldBe true
            profile.isEnabled(1, PhaseType.MAIN2) shouldBe true
            profile.isEnabled(1, PhaseType.UPKEEP) shouldBe false
            profile.isEnabled(1, PhaseType.DRAW) shouldBe false
            profile.isEnabled(1, PhaseType.END_OF_TURN) shouldBe false

            // AI engine-internal stops (for combat logic)
            profile.isEnabled(2, PhaseType.COMBAT_BEGIN) shouldBe true
            profile.isEnabled(2, PhaseType.COMBAT_DECLARE_ATTACKERS) shouldBe true
            profile.isEnabled(2, PhaseType.COMBAT_DECLARE_BLOCKERS) shouldBe true
            profile.isEnabled(2, PhaseType.END_OF_TURN) shouldBe true
            profile.isEnabled(2, PhaseType.MAIN1) shouldBe false
            profile.isEnabled(2, PhaseType.MAIN2) shouldBe false
        }

        test("createDefaults — AI stops are independent from human stops") {
            val profile = PhaseStopProfile.createDefaults(humanPlayerId = 10, aiPlayerId = 20)

            // Clearing a human stop doesn't affect AI
            profile.setEnabled(10, PhaseType.MAIN1, false)
            profile.isEnabled(10, PhaseType.MAIN1) shouldBe false
            profile.isEnabled(20, PhaseType.COMBAT_BEGIN) shouldBe true
        }

        test("setEnabled adds and removes stops") {
            val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)

            // Add a non-default stop
            profile.isEnabled(1, PhaseType.UPKEEP) shouldBe false
            profile.setEnabled(1, PhaseType.UPKEEP, true)
            profile.isEnabled(1, PhaseType.UPKEEP) shouldBe true

            // Remove a default stop
            profile.setEnabled(1, PhaseType.MAIN1, false)
            profile.isEnabled(1, PhaseType.MAIN1) shouldBe false
        }

        test("unknown player ID returns false") {
            val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
            profile.isEnabled(999, PhaseType.MAIN1) shouldBe false
        }

        test("empty profile has no stops") {
            val profile = PhaseStopProfile.empty()
            profile.isEnabled(1, PhaseType.MAIN1) shouldBe false
            profile.isEnabled(2, PhaseType.COMBAT_BEGIN) shouldBe false
        }

        test("createTwoPlayerDefaults — both get human stops") {
            val profile = PhaseStopProfile.createTwoPlayerDefaults(player1Id = 1, player2Id = 2)

            profile.isEnabled(1, PhaseType.MAIN1) shouldBe true
            profile.isEnabled(1, PhaseType.MAIN2) shouldBe true
            profile.isEnabled(2, PhaseType.MAIN1) shouldBe true
            profile.isEnabled(2, PhaseType.MAIN2) shouldBe true

            // Neither gets AI-style stops
            profile.isEnabled(1, PhaseType.COMBAT_BEGIN) shouldBe false
            profile.isEnabled(2, PhaseType.COMBAT_BEGIN) shouldBe false
        }

        test("getEnabled returns current stop set") {
            val profile = PhaseStopProfile.createDefaults(humanPlayerId = 1, aiPlayerId = 2)
            val humanStops = profile.getEnabled(1)
            humanStops shouldBe setOf(
                PhaseType.MAIN1,
                PhaseType.COMBAT_DECLARE_ATTACKERS,
                PhaseType.COMBAT_DECLARE_BLOCKERS,
                PhaseType.MAIN2,
            )
        }

        test("forPhaseKey maps canonical phases") {
            PhaseStopProfile.forPhaseKey("MAIN1") shouldBe PhaseType.MAIN1
            PhaseStopProfile.forPhaseKey("COMBAT_DECLARE_ATTACKERS") shouldBe PhaseType.COMBAT_DECLARE_ATTACKERS
            PhaseStopProfile.forPhaseKey("CLEANUP") shouldBe PhaseType.CLEANUP
        }

        test("forPhaseKey returns null for non-canonical or invalid") {
            PhaseStopProfile.forPhaseKey("UNTAP") shouldBe null
            PhaseStopProfile.forPhaseKey("NONSENSE") shouldBe null
        }
    })
