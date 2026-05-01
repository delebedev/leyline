package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId

/**
 * Ward {N} mana tax — opponent's spell targets a permanent with `Ward {<mana>}`,
 * Forge fires a Counter trigger with `UnlessCost`, the targeting player is
 * offered the tax via `OptionalActionMessage`. Accept → mana auto-taps and
 * the override returns `true` (Counter SA's effect is suppressed by Forge).
 * Decline → override returns `false`, Counter SA proceeds.
 *
 * Pins:
 * - `payCostToPreventEffect` routes mana-cost Ward through the
 *   `OptionalActionGate` (NOT the shock-land / echo / cumulative-upkeep paths).
 * - The cost payer is `this.player` (the controller whose `PlayerController`
 *   Forge dispatched on), NOT `sa.activatingPlayer` — Forge sets the latter
 *   to the warded permanent's controller (the trigger's "you"), which is
 *   the wrong seat. ComputerUtilMana auto-tap runs against the right seat's
 *   lands.
 * - Auto-response is wired via [MatchFlowHarness.drainSink] (default
 *   AllowYes, flipped via [MatchFlowHarness.declineNextOptionalAction] for
 *   the decline branch).
 *
 * The two branches are differentiated empirically by which lands the auto-tap
 * solver consumed: accept taps the {2} Forests on top of Bolt's Mountain;
 * decline taps only the Mountain.
 */
class WardTaxTest :
    InteractionTest({

        test("accept — auto-tap consumes the Ward {2} on top of Bolt's {R}") {
            startPuzzleFile("puzzles/ward-tax.pzl")

            val ai = harness.bridge.getPlayer(SeatId(2))!!
            castSpellByName("Lightning Bolt").shouldBeTrue()
            val targetIid = instanceIdOf("Sovereign Okinec Ahau", player = ai)
            // selectTargets drains the sink, which auto-accepts the Ward OAM
            // (default AllowYes). On accept, payWardManaTax returns true after
            // ComputerUtilMana taps lands for the {2} tax. The Counter SA's
            // effect is suppressed via Forge's `handleUnlessCost`.
            selectTargets(listOf(targetIid))

            // Both Forests tapped for the {2} Ward tax + Mountain tapped for
            // Bolt's {R} = 3 lands tapped total.
            val tapped =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isTapped }
                    .map { it.name }
            tapped.contains("Mountain").shouldBeTrue()
            tapped.count { it == "Forest" } shouldBe 2

            // Bolt left the stack — either to graveyard via resolution or via
            // the Counter SA. The decline test below pins the Forest-untapped
            // case to confirm the accept path actually drained mana.
            human
                .getZone(ZoneType.Graveyard)
                .cards
                .any { it.name == "Lightning Bolt" }
                .shouldBeTrue()
        }

        test("decline — Counter SA proceeds, Forests stay untapped") {
            startPuzzleFile("puzzles/ward-tax.pzl")

            val ai = harness.bridge.getPlayer(SeatId(2))!!
            castSpellByName("Lightning Bolt").shouldBeTrue()
            val targetIid = instanceIdOf("Sovereign Okinec Ahau", player = ai)

            // Pre-seed decline: drainSink auto-responds CancelNo on the Ward
            // OAM. payWardManaTax returns false; the Counter SA proceeds and
            // counters Bolt before any mana is drained for the {2}.
            harness.declineNextOptionalAction()
            selectTargets(listOf(targetIid))

            // Sovereign Okinec Ahau intact, undamaged — Bolt was countered.
            val sovereign = ai.getZone(ZoneType.Battlefield).cards.firstOrNull { it.name == "Sovereign Okinec Ahau" }
            sovereign?.damage shouldBe 0

            // Bolt landed in graveyard via the Counter SA, not via resolution.
            human
                .getZone(ZoneType.Graveyard)
                .cards
                .any { it.name == "Lightning Bolt" }
                .shouldBeTrue()

            // Bolt's {R} tapped Mountain; Forests stay untapped because
            // Ward was declined and the {2} tax was never paid.
            val tapped =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isTapped }
                    .map { it.name }
            tapped.contains("Mountain").shouldBeTrue()
            tapped.contains("Forest").shouldBeFalse()
        }
    })
