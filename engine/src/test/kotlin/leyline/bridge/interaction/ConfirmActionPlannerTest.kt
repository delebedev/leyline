package leyline.bridge.interaction

import forge.card.GamePieceType
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.player.PlayerActionConfirmMode
import forge.game.spellability.AbilitySub
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest

class ConfirmActionPlannerTest :
    BoardTest({
        lateinit var board: Board
        // Commander view updates and Paradigm traits require real Forge card context, not a running game loop.
        beforeEach { board = startWithBoard { _, _, _ -> } }

        fun confirmCard(id: Int): Card = Card(id, board.game).also { it.name = "Synthetic source" }

        fun confirmAbility(
            api: ApiType,
            params: Map<String, String> = emptyMap(),
        ): AbilitySub = AbilitySub(api, confirmCard(1), null, params)

        fun paradigmCopy(): Card =
            confirmCard(20).also { copy ->
                copy.gamePieceType = GamePieceType.TOKEN
                copy.copiedPermanent = addCard("Germination Practicum", board.human, ZoneType.Hand)
            }

        test("optional confirmation shapes carry distinct defaults and snapshot policy ahead of parity") {
            data class Case(
                val api: ApiType,
                val params: Map<String, String>,
                val default: Boolean,
                val snapshot: Boolean,
                val promptId: Int?,
            )
            val cases =
                listOf(
                    Case(ApiType.Endure, emptyMap(), true, false, PromptIds.ENDURE_PUT_COUNTERS),
                    Case(ApiType.CopySpellAbility, mapOf("Optional" to "True"), false, true, null),
                    Case(ApiType.RearrangeTopOfLibrary, mapOf("MayShuffle" to "True"), false, true, PromptIds.OPTIONAL_SHUFFLE),
                )
            for (case in cases) {
                val sa = confirmAbility(case.api, case.params)
                val shown = confirmCard(42)
                val plan =
                    ConfirmActionPlanner
                        .plan(sa, null, listOf("Odd", "Even"), shown)
                        .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                assertSoftly {
                    plan.hostCard shouldBe shown
                    plan.defaultOnTimeout shouldBe case.default
                    plan.forceSnapshotBeforePrompt shouldBe case.snapshot
                    plan.customPromptId shouldBe case.promptId
                }
            }
        }

        test("commander destination precedes optional shapes but follows Paradigm acceptance") {
            val commander = confirmCard(42).also { it.setCommander(true) }
            for (sa in listOf(
                confirmAbility(ApiType.Endure),
                confirmAbility(ApiType.CopySpellAbility, mapOf("Optional" to "True")),
                confirmAbility(ApiType.RearrangeTopOfLibrary, mapOf("MayShuffle" to "True")),
            )) {
                ConfirmActionPlanner.plan(sa, PlayerActionConfirmMode.ChangeZoneToAltDestination, listOf("Odd", "Even"), commander) shouldBe
                    ConfirmActionPlan.CommanderReturn(commander)
            }
            val copy = paradigmCopy().also { it.setCommander(true) }
            ConfirmActionPlanner.plan(
                confirmAbility(ApiType.Endure),
                PlayerActionConfirmMode.ChangeZoneToAltDestination,
                null,
                copy,
            ) shouldBe
                ConfirmActionPlan.Accept
        }

        test("shown card owns commander classification instead of an unrelated ability host") {
            val commander = confirmCard(1).also { it.setCommander(true) }
            val sa = AbilitySub(ApiType.Draw, commander, null, emptyMap())
            assertSoftly {
                ConfirmActionPlanner.plan(sa, PlayerActionConfirmMode.ChangeZoneToAltDestination, null, null) shouldBe
                    ConfirmActionPlan.CommanderReturn(commander)
                ConfirmActionPlanner
                    .plan(sa, PlayerActionConfirmMode.ChangeZoneToAltDestination, null, confirmCard(2))
                    .shouldBeInstanceOf<ConfirmActionPlan.Choice.AffirmativeDefault>()
                ConfirmActionPlanner
                    .plan(sa, null, null, commander)
                    .shouldBeInstanceOf<ConfirmActionPlan.Choice.AffirmativeDefault>()
            }
        }

        test("only the complete Paradigm free cast or an explicitly shown copy auto-accepts") {
            val copy = paradigmCopy()
            val sa = AbilitySub(ApiType.CopySpellAbility, copy, null, mapOf("Optional" to "True", "WithoutManaCost" to "True"))
            sa.isCastFromPlayEffect = true
            ConfirmActionPlanner.plan(sa, null, null, null) shouldBe ConfirmActionPlan.Accept
            sa.isCastFromPlayEffect = false
            ConfirmActionPlanner.plan(sa, null, null, null).shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
            val ordinary = confirmAbility(ApiType.Draw, mapOf("WithoutManaCost" to "True"))
            ordinary.isCastFromPlayEffect = true
            ConfirmActionPlanner.plan(ordinary, null, null, null).shouldBeInstanceOf<ConfirmActionPlan.Choice.AffirmativeDefault>()
            ConfirmActionPlanner.plan(ordinary, null, null, copy) shouldBe ConfirmActionPlan.Accept
            val noFreeCast = AbilitySub(ApiType.Draw, copy, null, emptyMap())
            noFreeCast.isCastFromPlayEffect = true
            ConfirmActionPlanner.plan(noFreeCast, null, null, null).shouldBeInstanceOf<ConfirmActionPlan.Choice.AffirmativeDefault>()
        }

        test("optional parameters on adjacent APIs and nonoptional siblings keep the explicit synchronous default") {
            val cases =
                listOf(
                    confirmAbility(ApiType.Draw, mapOf("Optional" to "True", "MayShuffle" to "True")),
                    confirmAbility(ApiType.CopySpellAbility),
                    confirmAbility(ApiType.RearrangeTopOfLibrary),
                )
            for (sa in cases) {
                val plan =
                    ConfirmActionPlanner
                        .plan(sa, null, null, null)
                        .shouldBeInstanceOf<ConfirmActionPlan.Choice.AffirmativeDefault>()
                assertSoftly {
                    plan.options shouldBe listOf("Yes", "No")
                    plan.sourceEntityId shouldBe sa.hostCard.id
                    plan.route.shouldBeInstanceOf<ResolvedPromptRoute.AutoResolve>()
                    plan.route.semantic shouldBe PromptSemantic.Generic
                }
            }
        }

        test("classification uses the callback subability rather than promoting an enclosing effect") {
            val callback = confirmAbility(ApiType.RearrangeTopOfLibrary, mapOf("MayShuffle" to "True"))
            val wrapper = confirmAbility(ApiType.Draw).also { it.subAbility = callback }
            ConfirmActionPlanner
                .plan(wrapper, null, null, null)
                .shouldBeInstanceOf<ConfirmActionPlan.Choice.AffirmativeDefault>()
            ConfirmActionPlanner
                .plan(callback, null, null, null)
                .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                .customPromptId shouldBe PromptIds.OPTIONAL_SHUFFLE
        }

        test("parity preserves offered order and rejects incomplete duplicate and oversized domains") {
            for ((labels, ids) in listOf(listOf("Odds", "Evens") to listOf(1, 0), listOf("even", "odd") to listOf(0, 1))) {
                val plan =
                    ConfirmActionPlanner
                        .plan(null, null, labels, null)
                        .shouldBeInstanceOf<ConfirmActionPlan.Choice.Parity>()
                assertSoftly {
                    plan.options shouldBe labels
                    plan.optionIds shouldBe ids
                    plan.route.semantic shouldBe PromptSemantic.StaticParityChoice
                    plan.sourceEntityId shouldBe null
                }
            }
            for (labels in listOf(listOf("Odd"), listOf("Odd", "Odds"), listOf("Odd", "Other"), listOf("Odd", "Even", "Other"))) {
                ConfirmActionPlanner.plan(null, null, labels, null).shouldBeInstanceOf<ConfirmActionPlan.Choice.AffirmativeDefault>()
            }
        }

        test("fallback snapshots supplied labels and normalizes only the static source id") {
            val labels = mutableListOf("Keep", "Change")
            val plan =
                ConfirmActionPlanner
                    .plan(confirmAbility(ApiType.Draw), null, labels, confirmCard(0))
                    .shouldBeInstanceOf<ConfirmActionPlan.Choice.AffirmativeDefault>()
            labels.clear()
            assertSoftly {
                plan.options shouldBe listOf("Keep", "Change")
                plan.sourceEntityId shouldBe null
                ConfirmActionPlanner
                    .plan(null, null, emptyList(), null)
                    .shouldBeInstanceOf<ConfirmActionPlan.Choice.AffirmativeDefault>()
                    .options shouldBe listOf("Yes", "No")
            }
        }
    })
