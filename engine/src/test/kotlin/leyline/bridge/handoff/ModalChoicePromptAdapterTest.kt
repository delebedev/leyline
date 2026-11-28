package leyline.bridge.handoff

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import forge.game.trigger.TriggerAlways
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.NonInteractiveScope
import leyline.bridge.bootstrap.GameBootstrap

class ModalChoicePromptAdapterTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        fun request() =
            PromptRequest(
                promptType = "choose_mode",
                message = "Choose a mode",
                options = listOf("one", "two"),
                defaultIndex = 0,
                route = ResolvedPromptRoute.ModalChoice(PromptSemantic.ModalChoice),
            )

        fun handle(): AbilitySub =
            AbilitySub(
                ApiType.Charm,
                Card(7, null).also { it.name = "Host" },
                null,
                emptyMap(),
            )

        fun adapter(
            timeoutMs: Long?,
            isGameLoopThread: Boolean,
            records: MutableList<PromptCallStatus>,
        ) = ModalChoicePromptAdapter(
            timeoutMs = timeoutMs,
            strict = false,
            isGameLoopThread = { isGameLoopThread },
            runtime = { error("fallback must not resolve a runtime") },
            prioritySignal = null,
            record = { _, outcome, _ -> records += outcome },
            labelRuntime = { error("fallback must not ask by label") },
        )

        fun answeredAdapter(
            result: ModalChoiceInteractionResult,
            records: MutableList<PromptCallStatus>,
        ) = ModalChoicePromptAdapter(
            timeoutMs = null,
            strict = false,
            isGameLoopThread = { true },
            runtime = {
                object : ModalChoiceInteractionRuntime {
                    override fun awaitSelection(
                        request: PromptRequest,
                        possible: List<AbilitySub>,
                        sourceCard: Card,
                        sourceAbility: SpellAbility,
                        timeoutMs: Long?,
                    ) = result
                }
            },
            prioritySignal = null,
            record = { _, status, _ -> records += status },
            labelRuntime = { error("identified modes must not ask by label") },
        )

        fun labelAdapter(
            records: MutableList<PromptCallStatus> = mutableListOf(),
            identifies: () -> Boolean = { false },
            answer: (PromptRequest) -> List<Int>,
        ) = ModalChoicePromptAdapter(
            timeoutMs = null,
            strict = false,
            isGameLoopThread = { true },
            runtime = {
                object : ModalChoiceInteractionRuntime {
                    override fun identifiesModes(
                        possible: List<AbilitySub>,
                        sourceCard: Card,
                        sourceAbility: SpellAbility,
                    ) = identifies()

                    override fun awaitSelection(
                        request: PromptRequest,
                        possible: List<AbilitySub>,
                        sourceCard: Card,
                        sourceAbility: SpellAbility,
                        timeoutMs: Long?,
                    ): ModalChoiceInteractionResult = error("unidentified modes must not open a modal window")
                }
            },
            prioritySignal = null,
            record = { _, status, _ -> records += status },
            labelRuntime = {
                object : StaticChoiceInteractionRuntime {
                    override fun shownOptions(request: PromptRequest) = request.options.indices.toList()

                    override fun awaitSelection(
                        request: PromptRequest,
                        timeoutMs: Long?,
                    ) = answer(request)
                }
            },
        )

        test("options without client mode identities are asked as a named label choice") {
            val asked = mutableListOf<PromptRequest>()
            val choices = listOf(handle(), handle())

            val result =
                labelAdapter { request -> listOf(1).also { asked += request } }
                    .request(request(), choices, choices[0].hostCard, choices[0])

            assertSoftly {
                result shouldBe listOf(choices[1])
                asked.single().route shouldBe PromptRouteResolver.resolve(PromptSemantic.NamedChoice)
                asked.single().options shouldBe listOf("one", "two")
                asked.single().staticOptionIds shouldBe listOf(1, 2)
                asked.single().allowCancel shouldBe true
            }
        }

        test("an optional vote asked by label abstains on timeout") {
            val records = mutableListOf<PromptCallStatus>()
            val choices = listOf(handle(), handle())
            val vote = request().copy(min = 0, route = ResolvedPromptRoute.ModalChoice(PromptSemantic.VoteChoice))

            val result =
                labelAdapter(records) { throw StaticChoiceInteractionTimeoutException() }
                    .request(vote, choices, choices[0].hostCard, choices[0])

            assertSoftly {
                result.shouldBeEmpty()
                records shouldBe listOf(PromptCallStatus.TIMEOUT)
            }
        }

        test("a cancelled cast-time label choice unwinds the cast") {
            val choices = listOf(handle(), handle())
            val records = mutableListOf<PromptCallStatus>()
            shouldThrow<ModalCastCancelledException> {
                labelAdapter(records) { throw StaticChoiceCancelledException() }
                    .request(request(), choices, choices[0].hostCard, choices[0])
            }
            records shouldBe listOf(PromptCallStatus.RESPONDED)
        }

        test("a repeatable choice is asked one pick at a time until the player stops") {
            val asked = mutableListOf<PromptRequest>()
            val answers = ArrayDeque(listOf(listOf(1), listOf(1), emptyList()))
            val choices = listOf(handle(), handle())
            val repeatable = request().copy(min = 2, max = 3, allowRepeat = true)

            val result =
                labelAdapter { request -> answers.removeFirst().also { asked += request } }
                    .request(repeatable, choices, choices[0].hostCard, choices[0])

            assertSoftly {
                result shouldBe listOf(choices[1], choices[1])
                asked.map { it.min to it.max } shouldBe listOf(1 to 1, 1 to 1, 0 to 1)
            }
        }

        test("a minimum above the offered options is capped for the label choice") {
            val asked = mutableListOf<PromptRequest>()
            val choices = listOf(handle(), handle())

            labelAdapter { request -> listOf(0, 1).also { asked += request } }
                .request(request().copy(min = 3, max = 3), choices, choices[0].hostCard, choices[0])

            asked.single().let { it.min to it.max } shouldBe (2 to 2)
        }

        test("a failed mode identity lookup is recorded as a prompt error") {
            val records = mutableListOf<PromptCallStatus>()
            val choice = handle()
            shouldThrow<IllegalStateException> {
                labelAdapter(records, identifies = { error("repository unavailable") }) { emptyList() }
                    .request(request(), listOf(choice), choice.hostCard, choice)
            }
            records shouldBe listOf(PromptCallStatus.ERROR)
        }

        test("cast cancellation unwinds without being recorded as a prompt error") {
            val records = mutableListOf<PromptCallStatus>()
            val choice = handle()
            val adapter = answeredAdapter(ModalChoiceInteractionResult(emptyList(), emptyList(), false, cancelled = true), records)
            shouldThrow<ModalCastCancelledException> {
                adapter.request(request(), listOf(choice), choice.hostCard, choice)
            }
            records shouldBe listOf(PromptCallStatus.RESPONDED)
        }

        test("trigger cancellation retains an empty response") {
            val records = mutableListOf<PromptCallStatus>()
            val choice =
                handle().also { it.setTrigger(TriggerAlways(emptyMap(), it.hostCard, true)) }
            answeredAdapter(ModalChoiceInteractionResult(emptyList(), emptyList(), false, cancelled = true), records)
                .request(request(), listOf(choice), choice.hostCard, choice)
                .shouldBeEmpty()
            records shouldBe listOf(PromptCallStatus.RESPONDED)
        }

        test("non-interactive fallback preserves status and selected default") {
            val records = mutableListOf<PromptCallStatus>()
            val choice = handle()

            val result =
                NonInteractiveScope.quiet {
                    adapter(null, isGameLoopThread = true, records).request(request(), listOf(choice), choice.hostCard, choice)
                }

            assertSoftly {
                result shouldBe listOf(choice)
                records shouldBe listOf(PromptCallStatus.NON_INTERACTIVE_SCOPE)
            }
        }

        test("non-game-thread fallback preserves status and selected default") {
            val records = mutableListOf<PromptCallStatus>()
            val choice = handle()

            val result = adapter(null, isGameLoopThread = false, records).request(request(), listOf(choice), choice.hostCard, choice)

            assertSoftly {
                result shouldBe listOf(choice)
                records shouldBe listOf(PromptCallStatus.NON_GAME_THREAD)
            }
        }

        test("optional vote with zero timeout abstains") {
            val records = mutableListOf<PromptCallStatus>()
            val choice = handle()
            val vote = request().copy(min = 0, route = ResolvedPromptRoute.ModalChoice(PromptSemantic.VoteChoice))
            adapter(0L, isGameLoopThread = true, records).request(vote, listOf(choice), choice.hostCard, choice).shouldBeEmpty()
        }

        test("zero timeout returns the default without a history entry or Forge resolution") {
            val records = mutableListOf<PromptCallStatus>()
            val choice = handle()

            val result = adapter(0L, isGameLoopThread = true, records).request(request(), listOf(choice), choice.hostCard, choice)

            assertSoftly {
                result shouldBe listOf(choice)
                records.shouldBeEmpty()
            }
        }
    })
