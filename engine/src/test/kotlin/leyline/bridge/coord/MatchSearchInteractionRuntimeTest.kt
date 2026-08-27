package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SearchSourceValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchSearchInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:search runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Forest
            humanlibrary=Mountain;Forest
            ailibrary=Forest
            """.trimIndent()

        fun request(
            board: Board,
            min: Int = 1,
            defaultIndex: Int = 0,
            source: SearchSourceValue? = null,
        ): PromptRequest {
            val candidates = board.human.getZone(ZoneType.Library).cards
            return PromptRequest(
                promptType = "choose_cards",
                message = "Search",
                options = candidates.map { it.name },
                min = min,
                max = 1,
                defaultIndex = defaultIndex,
                candidateRefs =
                    candidates.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Library.name)
                    },
                route = ResolvedPromptRoute.Search(PromptSemantic.Search),
                searchSource = source,
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedSearchInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.search.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.search.current()
            }
            return checkNotNull(published)
        }

        test("initial cut publishes library objects and SearchReq atomically") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.search.awaitSearch(request(board), 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val batches = coordinator.drain(SeatId(1))
            val batch = batches.single()
            val requestIndex = batch.indexOfFirst { it.hasSearchReq() }
            val search = batch[requestIndex]
            val libraryObjects =
                batch
                    .take(requestIndex)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .map { it.instanceId }
                    .toSet()

            assertSoftly {
                batch.map {
                    when {
                        it.hasGameStateMessage() -> "state"
                        it.hasSearchReq() -> "search"
                        else -> "other"
                    }
                } shouldContainExactly listOf("state", "state", "search")
                search.searchReq.sourceId shouldBe 0
                search.gameStateId shouldBe published.gameStateId
                search.searchReq.itemsToSearchList.shouldHaveSize(2)
                search.searchReq.itemsSoughtList.shouldHaveSize(2)
                search.searchReq.zonesToSearchList shouldContainExactly listOf(ZoneIds.libraryOf(SeatId(1)))
                search.searchReq.maxFind shouldBe 1
                libraryObjects.containsAll(search.searchReq.itemsToSearchList) shouldBe true
                finished.count shouldBe 1
            }

            val selected = search.searchReq.itemsSoughtList.first()
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()
            assertSoftly {
                coordinator.search.submit(published.interactionId, published.gameStateId + 1, listOf(selected)) shouldBe false
                coordinator.search.submit(published.interactionId, published.gameStateId, listOf(selected, selected)) shouldBe false
                coordinator.search.submit(published.interactionId, published.gameStateId, listOf(Int.MAX_VALUE)) shouldBe false
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                finished.count shouldBe 1
            }
            assertSoftly {
                coordinator.search.submit(published.interactionId, published.gameStateId, listOf(selected)) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(0)
                coordinator.search
                    .current()
                    .shouldBeNull()
            }
        }

        test("source-free fail-to-find and typecycling source preserve exact SearchReq shapes") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val sourceCard =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator.search.awaitSearch(
                        request(
                            board,
                            min = 0,
                            source =
                                SearchSourceValue(
                                    hostCardId = ForgeCardId(sourceCard.id),
                                    forgeAbilityId = 98_765,
                                    abilityOnStack = true,
                                    typeCycling = true,
                                ),
                        ),
                        3_000,
                    ),
                )
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val requestMessage = coordinator.drain(SeatId(1)).flatten().single { it.hasSearchReq() }
            assertSoftly {
                requestMessage.prompt.promptId shouldBe PromptIds.SEARCH_TYPECYCLING
                requestMessage.searchReq.sourceId shouldBeGreaterThan 0
                requestMessage.searchReq.sourceId shouldNotBe
                    requestMessage.prompt.parametersList
                        .first()
                        .numberValue
                coordinator.search.submit(published.interactionId, published.gameStateId, emptyList()) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(2)
            }
        }

        test("response invalidates the reveal baseline before the engine resumes") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFinished = CountDownLatch(1)
            Thread {
                coordinator.search.awaitSearch(request(board), 3_000)
                engineFinished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val selected =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSearchReq() }
                    .searchReq.itemsSoughtList
                    .first()
            val resetObserved = CountDownLatch(1)
            val allowRelease = CountDownLatch(1)
            val baseline = AtomicReference<Any?>()
            coordinator.search.afterBaselineResetBeforeRelease = {
                baseline.set(
                    board.bridge
                        .projectionStateSnapshot()
                        .viewerCursors[SeatId(1)]
                        ?.previousSnapshot,
                )
                resetObserved.countDown()
                check(allowRelease.await(3, TimeUnit.SECONDS))
            }
            val accepted = AtomicReference<Boolean>()
            val submitFinished = CountDownLatch(1)
            Thread {
                try {
                    accepted.set(coordinator.search.submit(published.interactionId, published.gameStateId, listOf(selected)))
                } finally {
                    submitFinished.countDown()
                }
            }.start()

            resetObserved.await(3, TimeUnit.SECONDS) shouldBe true
            assertSoftly {
                baseline.get().shouldBeNull()
                engineFinished.count shouldBe 1
            }
            allowRelease.countDown()
            assertSoftly {
                engineFinished.await(3, TimeUnit.SECONDS) shouldBe true
                submitFinished.await(3, TimeUnit.SECONDS) shouldBe true
                accepted.get() shouldBe true
            }
            coordinator.search.afterBaselineResetBeforeRelease = null
        }

        test("stale shared baseline reset terminalizes search and releases its waiter") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val waiterFailure = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            Thread {
                try {
                    coordinator.search.awaitSearch(request(board), null)
                } catch (ex: Throwable) {
                    waiterFailure.set(ex)
                } finally {
                    finished.countDown()
                }
            }.start()
            val published = awaitPublished(coordinator)
            val selected =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSearchReq() }
                    .searchReq.itemsSoughtList
                    .first()
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.search.beforeBaselineResetInstall = {
                board.bridge.getOrAllocInstanceId(ForgeCardId(9_999_999))
            }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.search.submit(published.interactionId, published.gameStateId, listOf(selected))
                }

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                waiterFailure.get() shouldBe terminal
                coordinator.failure() shouldBe terminal
                coordinator.search.current().shouldBeNull()
                board.bridge.committedSequence() shouldBe prior.sequence
                coordinator.drain(SeatId(1)).shouldBeEmpty()
            }
        }

        test("bridge timeout returns the configured default and requests later progression") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val signal = PrioritySignal()
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            val bridge =
                InteractivePromptBridge(timeoutMs = 25, prioritySignal = signal).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }
            Thread {
                result.set(bridge.requestChoice(request(board, min = 0, defaultIndex = 1)))
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val publishedSequence = board.bridge.committedSequence()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(1)
                signal.awaitSignal(3_000) shouldBe true
                board.bridge.committedSequence() shouldBe publishedSequence
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.search
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
        }
    })
