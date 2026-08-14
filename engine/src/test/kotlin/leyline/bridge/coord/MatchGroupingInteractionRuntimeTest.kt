package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.handoff.GroupingInteractionResult
import leyline.bridge.handoff.GroupingInteractionRuntime
import leyline.bridge.handoff.GroupingSourceValue
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRecord
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.SubZoneType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchGroupingInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:grouping runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Island
            humanlibrary=Mountain;Forest
            ailibrary=Forest
            """.trimIndent()

        fun cards(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Library)
                .cards
                .toList()
                .take(2)

        fun request(
            board: Board,
            context: GroupingContext,
        ): PromptRequest {
            val options = cards(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val semantic = if (context == GroupingContext.Surveil) PromptSemantic.GroupingSurveil else PromptSemantic.GroupingScry
            return PromptRequest(
                promptType = "choose_cards",
                message = "Arrange cards",
                options = options.map { it.name },
                min = 0,
                max = options.size,
                defaultIndex = 0,
                candidateRefs =
                    options.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Library.name)
                    },
                route = ResolvedPromptRoute.Grouping(semantic, context),
                groupingSource = GroupingSourceValue(ForgeCardId(source.id), 0, false),
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedGroupingInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.grouping.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.grouping.current()
            }
            return checkNotNull(published)
        }

        test("Scry publishes private candidates with GroupReq and preserves the exact top order") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val result = AtomicReference<GroupingInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.groupingRuntime(SeatId(1)).awaitGrouping(request(board, GroupingContext.Scry_a0f6), options, 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val batch = coordinator.drain(SeatId(1)).single()
            val state = batch.single { it.hasGameStateMessage() }.gameStateMessage
            val group = batch.single { it.hasGroupReq() }
            val reversed = group.groupReq.instanceIdsList.reversed()
            val exposed = state.gameObjectsList.filter { it.instanceId in group.groupReq.instanceIdsList }

            assertSoftly {
                batch.map { it.type } shouldContainExactly
                    listOf(
                        wotc.mtgo.gre.external.messaging.Messages.GREMessageType.GameStateMessage_695e,
                        wotc.mtgo.gre.external.messaging.Messages.GREMessageType.GroupReq_695e,
                    )
                group.gameStateId shouldBe published.gameStateId
                group.prompt.promptId shouldBe PromptIds.GROUP_SCRY
                group.groupReq.context shouldBe GroupingContext.Scry_a0f6
                group.groupReq.groupSpecsList[1].subZoneType shouldBe SubZoneType.Bottom
                group.allowCancel shouldBe AllowCancel.No_a526
                group.groupReq.sourceId shouldBe
                    board.instanceId(
                        board.human
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .single()
                            .id,
                    )
                exposed.size shouldBe 2
                exposed.all { it.visibility == Visibility.Private && it.viewersList == listOf(1) } shouldBe true
                coordinator.grouping.submit(published.interactionId, published.gameStateId, reversed, emptyList()) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                (result.get().topHandles[0] === options[1]) shouldBe true
                (result.get().topHandles[1] === options[0]) shouldBe true
            }
            coordinator.grouping.finalizeArrangement(result.get(), result.get().topHandles, emptyList())
            coordinator.grouping.pollArrangement(SeatId(2), GroupingContext.Scry_a0f6).shouldBeNull()
            val arranged = coordinator.grouping.pollArrangement(SeatId(1), GroupingContext.Scry_a0f6).shouldNotBeNull()
            arranged.topIds shouldContainExactly reversed
            coordinator.grouping.current().shouldBeNull()
        }

        test("Surveil maps the exact away handle and rejects stale or incomplete partitions") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val result = AtomicReference<GroupingInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.groupingRuntime(SeatId(1)).awaitGrouping(request(board, GroupingContext.Surveil), options, 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val group = coordinator.drain(SeatId(1)).flatten().single { it.hasGroupReq() }
            val ids = group.groupReq.instanceIdsList
            assertSoftly {
                group.prompt.promptId shouldBe PromptIds.GROUP_SURVEIL
                group.groupReq.groupSpecsList[1].zoneType shouldBe wotc.mtgo.gre.external.messaging.Messages.ZoneType.Graveyard
                coordinator.grouping.submit(published.interactionId, published.gameStateId + 1, ids, emptyList()) shouldBe false
                coordinator.grouping.submit(published.interactionId, published.gameStateId, listOf(ids[0]), emptyList()) shouldBe false
                coordinator.grouping.submit(published.interactionId, published.gameStateId, listOf(ids[0], ids[0]), emptyList()) shouldBe
                    false
                coordinator.grouping.submit(published.interactionId, published.gameStateId, listOf(ids[0]), listOf(Int.MAX_VALUE)) shouldBe
                    false
                coordinator.grouping.submit(published.interactionId, published.gameStateId, listOf(ids[0]), listOf(ids[1])) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                (result.get().awayHandles.single() === options[1]) shouldBe true
                coordinator.grouping.submit(published.interactionId, published.gameStateId, ids, emptyList()) shouldBe false
            }
            coordinator.grouping.finalizeArrangement(result.get(), result.get().topHandles, result.get().awayHandles)
            coordinator.grouping
                .pollArrangement(SeatId(1), GroupingContext.Surveil)
                .shouldNotBeNull()
                .awayIds shouldContainExactly listOf(ids[1])
        }

        test("triggered Grouping uses the exact projected stack ability instead of its host card") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val host =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val abilityId = 98765
            val triggered =
                request(board, GroupingContext.Surveil).copy(
                    groupingSource = GroupingSourceValue(ForgeCardId(host.id), abilityId, abilityOnStack = true),
                )
            val result = AtomicReference<GroupingInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.groupingRuntime(SeatId(1)).awaitGrouping(triggered, options, 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val group = coordinator.drain(SeatId(1)).flatten().single { it.hasGroupReq() }
            val exactAbilityId =
                board.bridge
                    .projectionStateSnapshot()
                    .identities
                    .forgeIdToInstanceId
                    .getValue(FrameIdResolver.triggerStackAbilityForgeId(abilityId))
                    .value
            val hostId = board.instanceId(host.id)
            assertSoftly {
                group.groupReq.sourceId shouldBe exactAbilityId
                group.groupReq.sourceId shouldNotBe hostId
                coordinator.grouping.submit(
                    published.interactionId,
                    published.gameStateId,
                    group.groupReq.instanceIdsList,
                    emptyList(),
                ) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
            }
            coordinator.grouping.finalizeArrangement(result.get(), result.get().topHandles, emptyList())
        }

        test("single-card history preserves the binary option domain for top away and timeout") {
            val board = startPuzzleAtMain1(puzzle)
            val card = cards(board).first()
            val singleRequest =
                request(board, GroupingContext.Scry_a0f6).copy(
                    promptType = "confirm",
                    options = listOf("Top of library", "Bottom of library"),
                    min = 1,
                    max = 1,
                    candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, card.id, ZoneType.Library.name)),
                )

            fun historyResult(
                away: Boolean,
                timedOut: Boolean,
            ): PromptRecord {
                val promptBridge = InteractivePromptBridge(timeoutMs = 25)
                promptBridge.groupingRuntime =
                    object : GroupingInteractionRuntime {
                        override fun awaitGrouping(
                            request: PromptRequest,
                            candidateHandles: List<Card>,
                            timeoutMs: Long?,
                        ): GroupingInteractionResult =
                            GroupingInteractionResult(
                                interactionId = "grouping-history",
                                context = GroupingContext.Scry_a0f6,
                                topHandles = if (away) emptyList() else listOf(card),
                                awayHandles = if (away) listOf(card) else emptyList(),
                                timedOut = timedOut,
                            )

                        override fun finalizeArrangement(
                            result: GroupingInteractionResult,
                            finalTopHandles: List<Card>,
                            awayHandles: List<Card>,
                        ) = Unit
                    }
                promptBridge.requestGrouping(singleRequest, listOf(card))
                return promptBridge.history.single()
            }

            assertSoftly {
                historyResult(away = false, timedOut = false).result shouldContainExactly listOf(0)
                historyResult(away = true, timedOut = false).result shouldContainExactly listOf(1)
                historyResult(away = false, timedOut = true).result shouldContainExactly listOf(0)
            }
        }
    })
