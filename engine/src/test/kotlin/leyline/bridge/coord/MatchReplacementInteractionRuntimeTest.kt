package leyline.bridge.coord

import forge.game.replacement.ReplaceGainLife
import forge.game.replacement.ReplacementEffect
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ReplacementInteractionResult
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActivatedActionEmitter
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.SearchResp
import wotc.mtgo.gre.external.messaging.Messages.SelectReplacementResp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchReplacementInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:replacement runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Fiery Temper;Fiery Temper
            humanbattlefield=Island
            ailibrary=Forest
            """.trimIndent()

        fun effects(board: Board): List<ReplacementEffect> =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .sortedBy { it.id }
                .flatMap { card -> card.keywords.flatMap { it.replacements } }

        fun request(effects: List<ReplacementEffect>): PromptRequest =
            PromptRequest(
                promptType = "select_replacement",
                message = "Choose which replacement effect applies first",
                options = effects.map { it.toString() },
                min = 1,
                max = 1,
                defaultIndex = 0,
                route = PromptRouteResolver.resolve(PromptSemantic.SelectReplacement),
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedReplacementInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.replacement.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.replacement.current()
            }
            return checkNotNull(published)
        }

        test("captures only ordered distinct Madness self-replacements") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            val initial = ReplacementWindowCapture(coordinator).initial(request(all), all)
            initial.shouldNotBeNull()
            assertSoftly {
                initial.value.options.map { it.originalOptionIndex } shouldBe listOf(0, 1)
                initial.value.options
                    .map { it.hostForgeCardId }
                    .distinct() shouldHaveSize 2
                (initial.handlesByOption[0] === all[0]) shouldBe true
                (initial.handlesByOption[1] === all[1]) shouldBe true
            }
        }

        test("capture refuses unsupported routes, identities, and option shapes before publication") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            val unsupported = ReplaceGainLife(emptyMap(), all.first().hostCard, false)
            val source = checkNotNull(all.first().hostCard)
            val unknownCard =
                forge.game.card.Card(999_999, source.paperCard, checkNotNull(board.bridge.getGame())).also {
                    it.currentState.copyFrom(source.currentState, false)
                    it.updateKeywords()
                }
            val unknownEffect = unknownCard.keywords.flatMap { it.replacements }.single()
            val cases =
                listOf(
                    request(all).copy(route = ResolvedPromptRoute.Search(PromptSemantic.Search)) to all,
                    request(all).copy(options = listOf("one")) to all,
                    request(all) to listOf(all.first(), unsupported),
                    request(all) to listOf(all.first(), all.first()),
                    request(all) to listOf(all.first(), unknownEffect),
                )

            cases.forEach { (candidateRequest, candidates) ->
                ReplacementWindowCapture(coordinator).initial(candidateRequest, candidates).shouldBeNull()
            }
            coordinator.replacement.current().shouldBeNull()
            coordinator.drain(SeatId(1)).shouldBeEmpty()
        }

        test("publishes the exact row envelope and returns the retained handle") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            val result = AtomicReference<ReplacementInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.replacement.awaitReplacement(request(all), all, 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val batch = coordinator.drain(SeatId(1)).flatten()
            val req = batch.single { it.hasSelectReplacementReq() }
            val rows = req.selectReplacementReq.replacementsList
            val state = batch.firstOrNull { it.hasGameStateMessage() }
            val expectedRows =
                all.map { effect ->
                    val host = checkNotNull(effect.hostCard)
                    val grpId = board.bridge.resolveGrpId(host)
                    val abilityGrpId = checkNotNull(board.bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.MADNESS))
                    val uniqueAbilityId =
                        checkNotNull(
                            ActivatedActionEmitter.uniqueAbilityIdFor(
                                checkNotNull(board.bridge.cardRepository.findByGrpId(grpId)),
                                abilityGrpId,
                            ),
                        )
                    Triple(checkNotNull(board.bridge.peekInstanceId(ForgeCardId(host.id))).value, uniqueAbilityId, abilityGrpId)
                }
            val baseline = board.bridge.projectionStateSnapshot()
            val sequence = board.counter.snapshot()
            val acceptedBefore = board.bridge.responseAcceptance.responsesAccepted()

            fun response(
                type: ClientMessageType = ClientMessageType.SelectReplacementResp_097b,
                gameStateId: Int = published.gameStateId,
                respId: Int = req.msgId,
                selectReplacementResp: SelectReplacementResp? = null,
                searchResp: SearchResp? = null,
            ): ClientToGREMessage =
                ClientToGREMessage
                    .newBuilder()
                    .setType(type)
                    .setGameStateId(gameStateId)
                    .setRespId(respId)
                    .apply {
                        selectReplacementResp?.let(::setSelectReplacementResp)
                        searchResp?.let(::setSearchResp)
                    }.build()

            fun replacementResponse(row: wotc.mtgo.gre.external.messaging.Messages.ReplacementEffect): ClientToGREMessage =
                response(
                    selectReplacementResp = SelectReplacementResp.newBuilder().setReplacement(row).build(),
                )

            fun reject(
                message: ClientToGREMessage,
                gameStateId: Int = published.gameStateId,
                respId: Int = req.msgId,
            ) {
                coordinator.acceptSettled(message, gameStateId, respId) shouldBe false
            }

            assertSoftly {
                req.type shouldBe GREMessageType.SelectReplacementReq_695e
                req.gameStateId shouldBe published.gameStateId
                req.prompt.promptId shouldBe PromptIds.SELECT_REPLACEMENT
                req.prompt.parametersCount shouldBe 0
                req.allowCancel shouldBe AllowCancel.No_a526
                req.allowUndo shouldBe true
                state?.gameStateMessage?.pendingMessageCount shouldBe 1
                rows shouldHaveSize 2
                rows.map { it.objectInstance } shouldBe expectedRows.map { it.first }
                rows.map { it.affectedObject } shouldBe expectedRows.map { it.first }
                rows.map { it.uniqueAbilityId } shouldBe expectedRows.map { it.second }
                rows.map { it.abilityGrpId } shouldBe expectedRows.map { it.third }
                rows.forEach { row ->
                    row.objectInstance shouldBe row.affectedObject
                    row.objectInstance shouldBeGreaterThan 0
                    row.uniqueAbilityId shouldBeGreaterThan 0
                    row.abilityGrpId shouldBeGreaterThan 0
                    row.replacementEffectId shouldBeGreaterThan 0
                    row.conferringObjectZcid shouldBe 0
                }
                rows.map { it.replacementEffectId }.distinct() shouldHaveSize 2
                req.selectReplacementReq.isOptional shouldBe false
                req.selectReplacementReq.replacementsType.number shouldBe 0
                req.selectReplacementReq.gameObjectSelectionsCount shouldBe 0
                reject(response(type = ClientMessageType.SearchResp_097b))
                reject(response())
                reject(response(selectReplacementResp = SelectReplacementResp.getDefaultInstance()))
                reject(response(searchResp = SearchResp.getDefaultInstance()))
                reject(replacementResponse(rows[0].toBuilder().clearUniqueAbilityId().build()))
                reject(replacementResponse(rows[0].toBuilder().setAffectedObject(rows[1].affectedObject).build()))
                reject(replacementResponse(rows[0].toBuilder().setReplacementEffectId(rows.maxOf { it.replacementEffectId } + 1).build()))
                reject(replacementResponse(rows[0]), gameStateId = published.gameStateId + 1)
                reject(replacementResponse(rows[0]), respId = req.msgId + 1)
                board.bridge.projectionStateSnapshot() shouldBe baseline
                board.counter.snapshot() shouldBe sequence
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore
                coordinator.replacement.current().shouldNotBeNull()
                finished.await(100, TimeUnit.MILLISECONDS) shouldBe false
                coordinator.acceptSettled(replacementResponse(rows[0]), published.gameStateId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                (result.get().handle === all[0]) shouldBe true
                result.get().optionIndex shouldBe 0
                coordinator.replacement.current().shouldBeNull()
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore + 1
                coordinator.acceptSettled(
                    wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.SelectReplacementResp_097b)
                        .setSelectReplacementResp(SelectReplacementResp.newBuilder().setReplacement(rows[0]))
                        .build(),
                    published.gameStateId,
                ) shouldBe false
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore + 1
            }
        }

        test("timeout returns the first retained handle and retires the slot") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            val bridge =
                leyline.bridge.handoff.InteractivePromptBridge(timeoutMs = 25, strict = false).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }
            val result = bridge.requestReplacement(request(all), all)
            result.shouldNotBeNull()
            assertSoftly {
                (result.handle === all[0]) shouldBe true
                result.timedOut shouldBe true
                coordinator.replacement.current().shouldBeNull()
            }
        }

        test("delivery retains the inner replacement cut ahead of an outer blocking cut") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val all = effects(board)
            val blockingFailure = AtomicReference<Throwable>()
            val blockingFinished = CountDownLatch(1)
            val blockingWaiter =
                Thread {
                    runCatching {
                        coordinator.awaitOptional(
                            BlockingInteraction.Optional(ForgeCardId(source.id), true, null, null),
                            3_000,
                            false,
                        )
                    }.onFailure(blockingFailure::set)
                    blockingFinished.countDown()
                }
            blockingWaiter.start()
            val blockingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (coordinator.currentBlockingInteraction() == null && System.nanoTime() < blockingDeadline) {
                Thread.onSpinWait()
            }
            checkNotNull(coordinator.currentBlockingInteraction())

            val replacementFailure = AtomicReference<Throwable>()
            val replacementFinished = CountDownLatch(1)
            val replacementWaiter =
                Thread {
                    runCatching { coordinator.replacement.awaitReplacement(request(all), all, null) }
                        .onFailure(replacementFailure::set)
                    replacementFinished.countDown()
                }
            replacementWaiter.start()
            awaitPublished(coordinator)

            val terminal = shouldThrow<PlaybackTerminalFailure> { coordinator.failDelivery(IllegalStateException("delivery unavailable")) }

            assertSoftly {
                terminal.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<leyline.bridge.handoff.ReplacementWindowValue>()
                blockingFinished.await(3, TimeUnit.SECONDS) shouldBe true
                replacementFinished.await(3, TimeUnit.SECONDS) shouldBe true
                blockingFailure.get() shouldBe terminal
                replacementFailure.get() shouldBe terminal
            }
            blockingWaiter.join(3_000)
            replacementWaiter.join(3_000)
        }

        test("central reset clears a pending replacement slot") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            val waiter = Thread { coordinator.replacement.awaitReplacement(request(all), all, 3_000) }
            waiter.start()
            val published = awaitPublished(coordinator)
            val row =
                coordinator
                    .drain(SeatId(1))
                    .flatMap { it }
                    .single { it.hasSelectReplacementReq() }
                    .selectReplacementReq
                    .replacementsList
                    .first()
            coordinator.prompts.settled.reset()
            coordinator.replacement.current().shouldBeNull()
            coordinator.acceptSettled(
                wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.SelectReplacementResp_097b)
                    .setSelectReplacementResp(SelectReplacementResp.newBuilder().setReplacement(row))
                    .build(),
                published.gameStateId,
            ) shouldBe false
            waiter.interrupt()
            waiter.join(3_000)
            waiter.isAlive shouldBe false
        }
    })
