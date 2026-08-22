package leyline.bridge.coord

import forge.game.replacement.ReplacementEffect
import forge.game.replacement.ReplacementHandler
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ReplacementInteractionResult
import leyline.bridge.handoff.ReplacementKeywordKind
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
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
            humanhand=Fiery Temper;Alms of the Vein
            humanbattlefield=Island
            ailibrary=Forest
            """.trimIndent()

        fun effects(board: Board): List<ReplacementEffect> =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .sortedBy { it.name }
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

        test("captures distinct keyword-backed self-replacements and refuses non-keyword effects") {
            val board = startPuzzleAtMain1(puzzle)
            val all = effects(board)
            val supported =
                ReplacementWindowCapture.initial(request(all), all)
            supported.shouldNotBeNull()
            assertSoftly {
                supported.value.options.map { it.keyword } shouldBe
                    listOf(ReplacementKeywordKind.Madness, ReplacementKeywordKind.Madness)
                supported.value.options
                    .map { it.hostForgeCardId }
                    .distinct() shouldHaveSize 2
                (supported.handlesByOption[0] === all[0]) shouldBe true
                (supported.handlesByOption[1] === all[1]) shouldBe true
            }

            val host =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            val synthetic = ReplacementHandler.parseReplacement("Event\$ Moved | ValidCard\$ Card.Self | Discard\$ True", host, true)
            ReplacementWindowCapture
                .initial(request(listOf(synthetic, all[0])), listOf(synthetic, all[0]))
                .shouldBeNull()
        }

        test("publishes the exact envelope and resumes the retained handle on a full-row echo") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            val result = AtomicReference<ReplacementInteractionResult?>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.replacement.awaitReplacement(request(all), all, 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val req = coordinator.drain(SeatId(1)).flatten().single { it.hasSelectReplacementReq() }
            val rows = req.selectReplacementReq.replacementsList
            val projection = board.bridge.projectionStateSnapshot()

            assertSoftly {
                req.type shouldBe GREMessageType.SelectReplacementReq_695e
                req.gameStateId shouldBe published.gameStateId
                req.prompt.promptId shouldBe PromptIds.SELECT_REPLACEMENT
                req.prompt.parametersList shouldHaveSize 0
                req.allowCancel shouldBe AllowCancel.No_a526
                req.allowUndo shouldBe true
                rows shouldHaveSize 2
                rows.forEach { row ->
                    row.objectInstance shouldBe row.affectedObject
                    row.objectInstance shouldBeGreaterThan 0
                    row.uniqueAbilityId shouldBeGreaterThan 0
                    row.abilityGrpId shouldBeGreaterThan 0
                    row.replacementEffectId shouldBeGreaterThan 0
                    row.conferringObjectZcid shouldBe 0
                }
                rows.map { it.replacementEffectId }.distinct() shouldHaveSize 2
                // Each row's abilityGrpId is the host card's own Madness ability id,
                // in the same order the engine offered the effects.
                rows.mapIndexed { index, row ->
                    val grpId = board.bridge.cardRepository.findGrpIdByName(all[index].hostCard.name)!!
                    row.abilityGrpId shouldBe
                        board.bridge.cardRepository.findKeywordAbilityGrpId(
                            grpId,
                            leyline.game.data.KeywordAbilityIds.MADNESS,
                        )
                }
                board.bridge.projectionStateSnapshot() shouldBe projection

                // Unknown / partial / mutated / stale rows never consume the window.
                coordinator.replacement.submitWire(
                    published.interactionId,
                    published.gameStateId,
                    rows[0].toBuilder().clearObjectInstance().build(),
                ) shouldBe
                    false
                coordinator.replacement.submitWire(
                    published.interactionId,
                    published.gameStateId,
                    rows[1].toBuilder().setAbilityGrpId(0).build(),
                ) shouldBe
                    false
                coordinator.replacement.submitWire(published.interactionId, published.gameStateId + 1, rows[0]) shouldBe false
                coordinator.replacement.current().shouldNotBeNull()

                coordinator.replacement.submitWire(published.interactionId, published.gameStateId, rows[0]) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().shouldNotBeNull()
                (result.get()!!.handle === all[0]) shouldBe true
                result.get()!!.optionIndex shouldBe 0
                coordinator.replacement.current().shouldBeNull()
            }
        }

        test("timeout selects the first published handle and retires the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            var timedOut = false
            val bridge =
                InteractivePromptBridge(timeoutMs = 25, strict = false).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                    it.timeoutListener = { timedOut = true }
                }
            val result = bridge.requestReplacement(request(all), all)
            val resolved = result ?: error("expected a replacement result")

            assertSoftly {
                (resolved.handle === all[0]) shouldBe true
                resolved.timedOut shouldBe true
                timedOut shouldBe true
                coordinator.replacement.current().shouldBeNull()
            }
        }

        test("reset clears a pending replacement window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            Thread {
                coordinator.replacement.awaitReplacement(request(all), all, 3_000)
            }.start()
            awaitPublished(coordinator)
            coordinator.replacement.reset()
            coordinator.replacement.current().shouldBeNull()
        }
    })
