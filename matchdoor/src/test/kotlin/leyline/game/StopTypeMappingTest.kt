package leyline.game

import forge.game.phase.PhaseType
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.mapping.StopTypeMapping
import wotc.mtgo.gre.external.messaging.Messages.SettingScope
import wotc.mtgo.gre.external.messaging.Messages.SettingStatus
import wotc.mtgo.gre.external.messaging.Messages.Stop
import wotc.mtgo.gre.external.messaging.Messages.StopType

/**
 * Tests for [StopTypeMapping] — pure mapping between Arena proto
 * [StopType] enums and Forge [PhaseType] enums.
 *
 * Needs conformance group: PhaseType.<clinit> requires Localizer
 * (loaded by initializeCardDatabase).
 */
class StopTypeMappingTest :
    FunSpec({

        tags(BoardTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        context("StopType -> PhaseType for all 11 types") {
            withData(
                StopType.UpkeepStep to PhaseType.UPKEEP,
                StopType.DrawStep to PhaseType.DRAW,
                StopType.PrecombatMainPhase to PhaseType.MAIN1,
                StopType.BeginCombatStep to PhaseType.COMBAT_BEGIN,
                StopType.DeclareAttackersStep to PhaseType.COMBAT_DECLARE_ATTACKERS,
                StopType.DeclareBlockersStep to PhaseType.COMBAT_DECLARE_BLOCKERS,
                StopType.CombatDamageStep to PhaseType.COMBAT_DAMAGE,
                StopType.EndCombatStep to PhaseType.COMBAT_END,
                StopType.PostcombatMainPhase to PhaseType.MAIN2,
                StopType.EndStep_ad1f to PhaseType.END_OF_TURN,
                StopType.FirstStrikeDamageStep to PhaseType.COMBAT_FIRST_STRIKE_DAMAGE,
            ) { (stopType, expected) ->
                StopTypeMapping.toPhaseType(stopType) shouldBe expected
            }
        }

        context("PhaseType -> StopType round-trip for all 11 types") {
            withData(
                StopType.UpkeepStep to PhaseType.UPKEEP,
                StopType.DrawStep to PhaseType.DRAW,
                StopType.PrecombatMainPhase to PhaseType.MAIN1,
                StopType.BeginCombatStep to PhaseType.COMBAT_BEGIN,
                StopType.DeclareAttackersStep to PhaseType.COMBAT_DECLARE_ATTACKERS,
                StopType.DeclareBlockersStep to PhaseType.COMBAT_DECLARE_BLOCKERS,
                StopType.CombatDamageStep to PhaseType.COMBAT_DAMAGE,
                StopType.EndCombatStep to PhaseType.COMBAT_END,
                StopType.PostcombatMainPhase to PhaseType.MAIN2,
                StopType.EndStep_ad1f to PhaseType.END_OF_TURN,
                StopType.FirstStrikeDamageStep to PhaseType.COMBAT_FIRST_STRIKE_DAMAGE,
            ) { (expected, phaseType) ->
                StopTypeMapping.toStopType(phaseType) shouldBe expected
            }
        }

        test("None_ad1f maps to null (no corresponding phase)") {
            StopTypeMapping.toPhaseType(StopType.None_ad1f).shouldBeNull()
        }

        test("phases without a StopType return null (UNTAP, CLEANUP)") {
            StopTypeMapping.toStopType(PhaseType.UNTAP).shouldBeNull()
            StopTypeMapping.toStopType(PhaseType.CLEANUP).shouldBeNull()
        }

        test("all 11 StopType values (excluding None) have a mapping") {
            val mapped =
                StopType
                    .values()
                    .filter { it != StopType.None_ad1f && it != StopType.UNRECOGNIZED }
                    .mapNotNull { StopTypeMapping.toPhaseType(it) }
            mapped.size shouldBe 11
        }

        // Property: for every StopType that has a forward mapping, the reverse
        // must round-trip back to the same StopType. Catches drops, swaps, and
        // duplicate PhaseType values in the forward table that would otherwise
        // be invisible at the individual-case level.
        test("StopType roundtrips through PhaseType for all mapped values") {
            val nonTerminal = StopType.values().toList() - setOf(StopType.None_ad1f, StopType.UNRECOGNIZED)
            for (st in nonTerminal) {
                val phase =
                    StopTypeMapping.toPhaseType(st)
                        ?: error("Unmapped non-terminal StopType: $st")
                val back = StopTypeMapping.toStopType(phase)
                back shouldBe st
            }
        }

        // --- Stop list parsing ---

        test("parse a list of Stop protos into enabled PhaseType set") {
            val stops =
                listOf(
                    stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Set),
                    stop(StopType.PostcombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Set),
                    stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe),
                )
            val enabled = StopTypeMapping.parseStops(stops, SettingScope.Team_ac6e)
            enabled shouldContain PhaseType.MAIN1
            enabled shouldContain PhaseType.MAIN2
            enabled shouldNotContain PhaseType.UPKEEP
        }

        test("only stops matching the requested scope are included") {
            val stops =
                listOf(
                    stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Set),
                    stop(StopType.DeclareAttackersStep, SettingScope.Opponents, SettingStatus.Set),
                )
            val teamStops = StopTypeMapping.parseStops(stops, SettingScope.Team_ac6e)
            teamStops shouldContain PhaseType.MAIN1
            teamStops shouldNotContain PhaseType.COMBAT_DECLARE_ATTACKERS

            val opponentStops = StopTypeMapping.parseStops(stops, SettingScope.Opponents)
            opponentStops shouldContain PhaseType.COMBAT_DECLARE_ATTACKERS
            opponentStops shouldNotContain PhaseType.MAIN1
        }

        test("AnyPlayer scope matches both Team and Opponents queries") {
            val stops =
                listOf(
                    stop(StopType.DrawStep, SettingScope.AnyPlayer, SettingStatus.Set),
                )
            val teamStops = StopTypeMapping.parseStops(stops, SettingScope.Team_ac6e)
            teamStops shouldContain PhaseType.DRAW

            val opponentStops = StopTypeMapping.parseStops(stops, SettingScope.Opponents)
            opponentStops shouldContain PhaseType.DRAW
        }
    })

private fun stop(
    type: StopType,
    scope: SettingScope,
    status: SettingStatus,
): Stop =
    Stop
        .newBuilder()
        .setStopType(type)
        .setAppliesTo(scope)
        .setStatus(status)
        .build()
