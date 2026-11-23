package leyline.bridge.interaction

import forge.card.GamePieceType
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.player.PlayerActionConfirmMode
import forge.game.spellability.AbilitySub
import forge.game.zone.ZoneType
import forge.util.Localizer
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.AutomaticAnswerRule
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
                    Case(ApiType.Repeat, mapOf("RepeatOptional" to "True"), false, true, null),
                    Case(ApiType.Repeat, mapOf("RepeatOptional" to "False"), false, true, null),
                )
            for (case in cases) {
                val sa = confirmAbility(case.api, case.params)
                val shown = confirmCard(42)
                val plan =
                    ConfirmActionPlanner
                        .plan(sa, null, null, listOf("Odd", "Even"), shown)
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
            val paradigm = ConfirmActionPlan.Automatic(AutomaticAnswerRule.ParadigmCopyCast)
            val commander = confirmCard(42).also { it.setCommander(true) }
            for (sa in listOf(
                confirmAbility(ApiType.Endure),
                confirmAbility(ApiType.CopySpellAbility, mapOf("Optional" to "True")),
                confirmAbility(ApiType.RearrangeTopOfLibrary, mapOf("MayShuffle" to "True")),
                confirmAbility(ApiType.Repeat, mapOf("RepeatOptional" to "True")),
            )) {
                ConfirmActionPlanner.plan(
                    sa,
                    PlayerActionConfirmMode.ChangeZoneToAltDestination,
                    null,
                    listOf("Odd", "Even"),
                    commander,
                ) shouldBe
                    ConfirmActionPlan.CommanderReturn(commander)
            }
            val copy = paradigmCopy().also { it.setCommander(true) }
            ConfirmActionPlanner.plan(
                confirmAbility(ApiType.Endure),
                PlayerActionConfirmMode.ChangeZoneToAltDestination,
                null,
                null,
                copy,
            ) shouldBe
                paradigm
        }

        test("optional zone transfer confirmation retains agency only in its resolving callback") {
            val move = confirmAbility(ApiType.ChangeZone, mapOf("Optional" to "True"))
            val plan =
                ConfirmActionPlanner
                    .plan(move, PlayerActionConfirmMode.ChangeZoneGeneral, null, null, null)
                    .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
            assertSoftly {
                plan.hostCard shouldBe move.hostCard
                plan.defaultOnTimeout shouldBe false
                plan.forceSnapshotBeforePrompt shouldBe true
                ConfirmActionPlanner.plan(move, null, null, null, null).shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                ConfirmActionPlanner
                    .plan(confirmAbility(ApiType.ChangeZone), PlayerActionConfirmMode.ChangeZoneGeneral, null, null, null)
                    .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                    .logContext shouldBe "confirmAction"
            }
        }

        test("shown card owns commander classification instead of an unrelated ability host") {
            val commander = confirmCard(1).also { it.setCommander(true) }
            val sa = AbilitySub(ApiType.Draw, commander, null, emptyMap())
            assertSoftly {
                ConfirmActionPlanner.plan(sa, PlayerActionConfirmMode.ChangeZoneToAltDestination, null, null, null) shouldBe
                    ConfirmActionPlan.CommanderReturn(commander)
                ConfirmActionPlanner
                    .plan(sa, PlayerActionConfirmMode.ChangeZoneToAltDestination, null, null, confirmCard(2))
                    .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                    .logContext shouldBe "confirmAction"
                ConfirmActionPlanner
                    .plan(sa, null, null, null, commander)
                    .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                    .hostCard shouldBe commander
            }
        }

        test("only the complete Paradigm free cast or an explicitly shown copy auto-accepts") {
            val paradigm = ConfirmActionPlan.Automatic(AutomaticAnswerRule.ParadigmCopyCast)
            val copy = paradigmCopy()
            val sa = AbilitySub(ApiType.CopySpellAbility, copy, null, mapOf("Optional" to "True", "WithoutManaCost" to "True"))
            sa.isCastFromPlayEffect = true
            ConfirmActionPlanner.plan(sa, null, null, null, null) shouldBe paradigm
            sa.isCastFromPlayEffect = false
            ConfirmActionPlanner.plan(sa, null, null, null, null).shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
            val ordinary = confirmAbility(ApiType.Draw, mapOf("WithoutManaCost" to "True"))
            ordinary.isCastFromPlayEffect = true
            ConfirmActionPlanner.plan(ordinary, null, null, null, null).shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
            ConfirmActionPlanner.plan(ordinary, null, null, null, copy) shouldBe paradigm
            val noFreeCast = AbilitySub(ApiType.Draw, copy, null, emptyMap())
            noFreeCast.isCastFromPlayEffect = true
            ConfirmActionPlanner.plan(noFreeCast, null, null, null, null).shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
        }

        test("optional parameters on adjacent APIs and nonoptional siblings ask through the optional gate") {
            val cases =
                listOf(
                    confirmAbility(ApiType.Draw, mapOf("Optional" to "True", "MayShuffle" to "True")),
                    confirmAbility(ApiType.CopySpellAbility),
                    confirmAbility(ApiType.RearrangeTopOfLibrary),
                    confirmAbility(ApiType.Repeat),
                    confirmAbility(ApiType.Draw, mapOf("RepeatOptional" to "True")),
                )
            for (sa in cases) {
                val plan =
                    ConfirmActionPlanner
                        .plan(sa, null, null, null, null)
                        .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                assertSoftly {
                    plan.hostCard shouldBe sa.hostCard
                    plan.defaultOnTimeout shouldBe false
                    plan.forceSnapshotBeforePrompt shouldBe true
                    plan.logContext shouldBe "confirmAction"
                    plan.customPromptId shouldBe null
                }
            }
        }

        test("classification uses the callback subability rather than promoting an enclosing effect") {
            for ((callback, promptId) in listOf(
                confirmAbility(ApiType.RearrangeTopOfLibrary, mapOf("MayShuffle" to "True")) to PromptIds.OPTIONAL_SHUFFLE,
                confirmAbility(ApiType.Repeat, mapOf("RepeatOptional" to "True")) to null,
            )) {
                val wrapper = confirmAbility(ApiType.Draw).also { it.subAbility = callback }
                ConfirmActionPlanner
                    .plan(wrapper, null, null, null, null)
                    .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                    .logContext shouldBe "confirmAction"
                ConfirmActionPlanner
                    .plan(callback, null, null, null, null)
                    .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                    .customPromptId shouldBe promptId
            }
        }

        test("parity preserves offered order and rejects incomplete duplicate and oversized domains") {
            for ((labels, ids) in listOf(listOf("Odds", "Evens") to listOf(1, 0), listOf("even", "odd") to listOf(0, 1))) {
                val plan =
                    ConfirmActionPlanner
                        .plan(null, null, null, labels, null)
                        .shouldBeInstanceOf<ConfirmActionPlan.Choice.Parity>()
                assertSoftly {
                    plan.options shouldBe labels
                    plan.optionIds shouldBe ids
                    plan.route.semantic shouldBe PromptSemantic.StaticParityChoice
                    plan.sourceEntityId shouldBe null
                }
            }
            for (labels in listOf(listOf("Odd"), listOf("Odd", "Odds"), listOf("Odd", "Other"), listOf("Odd", "Even", "Other"))) {
                ConfirmActionPlanner.plan(null, null, null, labels, null).shouldBeInstanceOf<ConfirmActionPlan.Choice.Labels>()
            }
        }

        test("Forge labels ask as a named choice that snapshots labels and normalizes only the static source id") {
            val labels = mutableListOf("Keep", "Change")
            val plan =
                ConfirmActionPlanner
                    .plan(confirmAbility(ApiType.Draw), null, null, labels, confirmCard(0))
                    .shouldBeInstanceOf<ConfirmActionPlan.Choice.Labels>()
            labels.clear()
            assertSoftly {
                plan.options shouldBe listOf("Keep", "Change")
                plan.sourceEntityId shouldBe null
                plan.route.semantic shouldBe PromptSemantic.NamedChoice
                plan.route.shouldBeInstanceOf<ResolvedPromptRoute.StaticChoice>()
            }
        }

        test("a Yes or No confirmation without a card to show asks as a named choice") {
            ConfirmActionPlanner
                .plan(null, null, "Continue?", emptyList(), null)
                .shouldBeInstanceOf<ConfirmActionPlan.Choice.Labels>()
                .options shouldBe listOf("Yes", "No")
        }

        test("explore and optional sacrifice confirmations ask about the shown card or ability source") {
            val revealed = confirmCard(42)
            ConfirmActionPlanner
                .plan(confirmAbility(ApiType.Explore), null, "[Explore] Put it in your graveyard?", null, revealed)
                .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                .hostCard shouldBe revealed
            val sacrifice = confirmAbility(ApiType.Sacrifice, mapOf("Optional" to "True"))
            ConfirmActionPlanner
                .plan(sacrifice, null, "Do you want to sacrifice?", null, null)
                .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                .hostCard shouldBe sacrifice.hostCard
        }

        test("named automatic rules cover the play-card, Discover and cancel-search confirmations only") {
            val localizer = Localizer.getInstance()
            val shown = confirmCard(42)
            val play = confirmAbility(ApiType.Play, mapOf("Optional" to "True"))
            val playMessage = localizer.getMessage("lblDoYouWantPlayCard", "Opt")
            val cancelSearch = localizer.getMessage("lblCancelSearchUpToSelectNumCards", "2")
            val search = confirmAbility(ApiType.ChangeZone, mapOf("Optional" to "True", "Origin" to "Library"))
            assertSoftly {
                ConfirmActionPlanner.plan(play, null, playMessage, null, shown) shouldBe
                    ConfirmActionPlan.Automatic(AutomaticAnswerRule.PlayEffectPrecedesOptionalCast)
                ConfirmActionPlanner.plan(
                    play,
                    null,
                    localizer.getMessage("lblDoYouWantPlayCardTransformed", "Opt"),
                    null,
                    shown,
                ) shouldBe ConfirmActionPlan.Automatic(AutomaticAnswerRule.PlayEffectPrecedesOptionalCast)
                ConfirmActionPlanner.plan(
                    confirmAbility(ApiType.Discover),
                    null,
                    localizer.getMessage("lblDiscoverChoice", "Opt"),
                    listOf("Cast", "Hand"),
                    shown,
                ) shouldBe ConfirmActionPlan.Automatic(AutomaticAnswerRule.PlayEffectPrecedesOptionalCast)
                ConfirmActionPlanner.plan(search, PlayerActionConfirmMode.ChangeZoneGeneral, cancelSearch, null, null) shouldBe
                    ConfirmActionPlan.Automatic(AutomaticAnswerRule.SearchSelectionSubmitted)
                // The same callbacks with other messages, or without a card to play, still ask.
                ConfirmActionPlanner
                    .plan(play, null, playMessage, null, null)
                    .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                ConfirmActionPlanner
                    .plan(play, null, "Do you want to play?", null, shown)
                    .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                ConfirmActionPlanner
                    .plan(search, PlayerActionConfirmMode.ChangeZoneGeneral, "Search your library?", null, null)
                    .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
                    .logContext shouldBe "confirmAction:OptionalZoneChange"
                ConfirmActionPlanner
                    .plan(search, null, cancelSearch, null, null)
                    .shouldBeInstanceOf<ConfirmActionPlan.OptionalAction>()
            }
        }
    })
