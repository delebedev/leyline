package leyline.bridge.coord

import forge.game.player.DelayedReveal
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.ZoneIds
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SearchWindowCaptureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:search ownership
            Goal:Win
            Turns:5

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
        ): PromptRequest {
            val cards = board.human.getZone(ZoneType.Library).cards
            return PromptRequest(
                promptType = "choose_cards",
                message = "Search",
                options = cards.map { it.name },
                min = min,
                max = 1,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, "Library")
                    },
                route = ResolvedPromptRoute.Search(PromptSemantic.Search),
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

        test("search capture uses the candidate library owner and keeps the full domain") {
            val board = startPuzzleAtMain1(puzzle.replace("ailibrary=Forest", "ailibrary=Centaur Courser;Forest;Forest"))
            val library = board.ai.getZone(ZoneType.Library).cards
            val creature = library.first { it.name == "Centaur Courser" }
            val search =
                request(board).copy(
                    options = listOf(creature.name),
                    candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, creature.id, ZoneType.Library.name)),
                )
            val value = SearchWindowCapture(board.bridge.cutCoordinator).capture(search)
            assertSoftly {
                value.searchedSeatId shouldBe SeatId(2)
                value.libraryCardIds shouldContainExactly library.map { ForgeCardId(it.id) }
                value.candidateCardIdsByOption shouldBe mapOf(0 to ForgeCardId(creature.id))
            }
        }

        test("empty search preserves an explicit library owner without inferring the chooser") {
            val board = startPuzzleAtMain1(puzzle)
            val empty = request(board, min = 0).copy(options = emptyList(), candidateRefs = emptyList(), searchedSeatId = SeatId(2))
            val value = SearchWindowCapture(board.bridge.cutCoordinator).capture(empty)
            assertSoftly {
                value.searchedSeatId shouldBe SeatId(2)
                value.libraryCardIds shouldContainExactly
                    board.ai
                        .getZone(ZoneType.Library)
                        .cards
                        .map { ForgeCardId(it.id) }
                value.candidateCardIdsByOption shouldBe emptyMap()
            }
            shouldThrow<IllegalStateException> {
                SearchWindowCapture(board.bridge.cutCoordinator).capture(empty.copy(searchedSeatId = null))
            }
        }

        test("capture rejects candidates from different libraries or outside the searched library") {
            val board = startPuzzleAtMain1(puzzle)
            val ours = request(board)
            val theirs =
                board.ai
                    .getZone(ZoneType.Library)
                    .cards
                    .single()
            val mixed =
                ours.copy(
                    candidateRefs =
                        ours.candidateRefs + PromptCandidateRefDto(2, PromptCandidateKind.Card, theirs.id, "Library"),
                )
            shouldThrow<IllegalStateException> { SearchWindowCapture(board.bridge.cutCoordinator).capture(mixed) }
            shouldThrow<IllegalArgumentException> {
                SearchWindowCapture(board.bridge.cutCoordinator).capture(ours.copy(searchedSeatId = SeatId(2)))
            }
            val land =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            shouldThrow<IllegalArgumentException> {
                SearchWindowCapture(board.bridge.cutCoordinator).capture(
                    ours.copy(candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, land.id, "Library"))),
                )
            }
        }

        listOf(false, true).forEach { grouped ->
            test("opponent library search projects only to its chooser and retires visibility (grouped=$grouped)") {
                val board = startPuzzleAtMain1(puzzle.replace("ailibrary=Forest", "ailibrary=Centaur Courser;Forest;Forest"))
                val coordinator = board.bridge.cutCoordinator
                coordinator.registerViewer(SeatId(2))
                coordinator.registerViewer(SeatId(3), ProjectionViewerRole.Observer)
                coordinator.drain(SeatId(1))
                val creature =
                    board.ai
                        .getZone(ZoneType.Library)
                        .cards
                        .first { it.name == "Centaur Courser" }
                val search =
                    request(board).copy(
                        options = listOf(creature.name),
                        candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, creature.id, "Library")),
                        searchedSeatId = SeatId(2),
                        searchGroupOptionIndices = if (grouped) listOf(listOf(0)) else emptyList(),
                    )
                val finished = CountDownLatch(1)
                Thread {
                    coordinator.search.awaitSearch(search, 3_000)
                    finished.countDown()
                }.start()
                val published = awaitPublished(coordinator)
                val messages = coordinator.drain(SeatId(1)).flatten()
                val req = messages.single { it.hasSearchReq() || it.hasSearchFromGroupsReq() }
                val zone = ZoneIds.libraryOf(SeatId(2))
                val objects =
                    messages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .filter { it.zoneId == zone }
                objects.shouldHaveSize(3)
                objects.all { it.viewersList == listOf(1) } shouldBe true
                val selected =
                    if (grouped) {
                        req.searchFromGroupsReq.groupsList
                            .single()
                            .idsList
                            .single()
                    } else {
                        req.searchReq.itemsSoughtList.single()
                    }
                if (grouped) {
                    req.searchFromGroupsReq.zonesToSearchList shouldContainExactly listOf(zone)
                } else {
                    req.searchReq.zonesToSearchList shouldContainExactly listOf(zone)
                    req.searchReq.itemsToSearchList.shouldHaveSize(3)
                }
                listOf(SeatId(2), SeatId(3)).forEach { viewer ->
                    coordinator
                        .drain(viewer)
                        .flatten()
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .filter { it.zoneId == zone }
                        .shouldBeEmpty()
                }
                val response =
                    if (grouped) {
                        leyline.testkit.groupedSearchResp(
                            5003,
                            listOf(selected),
                            1,
                        )
                    } else {
                        leyline.testkit.searchResp(listOf(selected))
                    }
                assertSoftly {
                    coordinator.acceptSettled(response, published.gameStateId) shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    board
                        .stateOnlyDiff()
                        .gameObjectsList
                        .filter { it.zoneId == zone }
                        .shouldBeEmpty()
                }
            }
        }

        listOf(false, true).forEach { opponent ->
            test("controller library search keeps complete card families private (opponent=$opponent)") {
                val library = "Brazen Borrower;Riling Dawnbreaker;Baithook Angler"
                val setup = puzzle + "\nhumanhand=Bribery"
                val board =
                    startPuzzleAtMain1(
                        if (opponent) {
                            setup.replace("ailibrary=Forest", "ailibrary=$library")
                        } else {
                            setup.replace("humanlibrary=Mountain;Forest", "humanlibrary=$library")
                        },
                    )
                val coordinator = board.bridge.cutCoordinator
                coordinator.registerViewer(SeatId(2))
                coordinator.registerViewer(SeatId(3), ProjectionViewerRole.Observer)
                coordinator.drain(SeatId(1))
                val owner = if (opponent) board.ai else board.human
                val cards = owner.getZone(ZoneType.Library).cards
                val ability =
                    board.human.hand
                        .card("Bribery")
                        .spellAbilities
                        .single()
                val finished = CountDownLatch(1)
                Thread {
                    board.bridge.promptBridge(SeatId(1)).setDiagnosticContext(board.game, Thread.currentThread())
                    board.human.controller.chooseSingleEntityForEffect(
                        cards,
                        DelayedReveal(cards, ZoneType.Library, owner.view),
                        ability,
                        "Search",
                        true,
                        owner,
                        null,
                    )
                    finished.countDown()
                }.start()
                val published = awaitPublished(coordinator)
                val messages = coordinator.drain(SeatId(1)).flatten()
                val zone = ZoneIds.libraryOf(SeatId(if (opponent) 2 else 1))
                val objects =
                    messages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .filter { it.zoneId == zone }
                assertSoftly {
                    objects.shouldHaveSize(6)
                    val parentIds =
                        objects
                            .filter {
                                it.type == GameObjectType.Card
                            }.map { it.instanceId }
                    objects.filter { it.type != GameObjectType.Card }.all {
                        it.parentId in
                            parentIds
                    } shouldBe
                        true
                    objects.all {
                        it.visibility == Visibility.Private &&
                            it.viewersList == listOf(1)
                    } shouldBe
                        true
                    objects.map { it.type }.toSet() shouldBe
                        setOf(
                            GameObjectType.Card,
                            GameObjectType.Adventure_a4aa,
                            GameObjectType.Omen_a4aa,
                            GameObjectType.DisturbBack,
                        )
                    listOf(SeatId(2), SeatId(3)).forEach { viewer ->
                        coordinator
                            .drain(viewer)
                            .flatten()
                            .filter { it.hasGameStateMessage() }
                            .flatMap { it.gameStateMessage.gameObjectsList }
                            .filter { it.zoneId == zone }
                            .shouldBeEmpty()
                    }
                }
                assertSoftly {
                    coordinator.acceptSettled(leyline.testkit.searchResp(emptyList()), published.gameStateId) shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    val retired = board.stateOnlyDiff()
                    retired.gameObjectsList.filter { it.zoneId == zone }.shouldBeEmpty()
                    (
                        retired.type == GameStateType.Full ||
                            retired.diffDeletedInstanceIdsList.containsAll(
                                objects.map { it.instanceId },
                            )
                    ) shouldBe
                        true
                }
            }
        }

        test("explicit public library reveal remains visible") {
            val board = startPuzzleAtMain1(puzzle)
            val cards = board.ai.getZone(ZoneType.Library).cards
            board.human.controller.reveal(cards, ZoneType.Library, board.ai, null, false)
            board
                .stateOnlyDiff()
                .gameObjectsList
                .filter {
                    it.type == GameObjectType.RevealedCard
                }.shouldHaveSize(1)
        }

        test("delayed non-search library reveal retains its callback behavior") {
            val board = startPuzzleAtMain1(puzzle + "\nhumanhand=Strategic Planning")
            val cards = board.ai.getZone(ZoneType.Library).cards
            val ability =
                board.human.hand
                    .card("Strategic Planning")
                    .spellAbilities
                    .single()
            board.human.controller.chooseSingleEntityForEffect(
                cards,
                DelayedReveal(cards, ZoneType.Library, board.ai.view),
                ability,
                "Look",
                false,
                board.ai,
                null,
            ) shouldBe cards.single()
            board
                .stateOnlyDiff()
                .gameObjectsList
                .filter {
                    it.type == GameObjectType.RevealedCard
                }.shouldHaveSize(1)
        }
    })
