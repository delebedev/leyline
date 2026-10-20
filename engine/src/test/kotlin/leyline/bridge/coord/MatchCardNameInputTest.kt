package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchCardNameInputTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:Card name lifecycle
            Goal:Survive
            Turns:3
            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Swamp
            humanhand=Demonic Consultation
            humanlibrary=Forest
            ailibrary=Plains
            """.trimIndent()

        test("card-name answers claim the exact window once and retain raw text") {
            val board = startPuzzleAtMain1(puzzle)
            val owner = board.bridge.cutCoordinator
            owner.drain(SeatId(1))
            val source =
                ForgeCardId(
                    board.human
                        .getZone(forge.game.zone.ZoneType.Battlefield)
                        .cards
                        .first()
                        .id,
                )
            val result = AtomicReference<String>()
            val failure = AtomicReference<Throwable>()
            val thread =
                Thread {
                    runCatching { owner.awaitCardName(BlockingInteraction.CardName(source, "Choose a card name."), 3_000) }
                        .onSuccess(result::set)
                        .onFailure(failure::set)
                }.also { it.start() }
            val pending = awaitCardNameWindow(owner, failure)
            val messages = owner.drain(SeatId(1)).flatten()
            assertSoftly {
                messages.count { it.hasStringInputReq() } shouldBe 1
                owner.submitCardNameAnswer("stale", pending.gameStateId, "Island") shouldBe false
                owner.submitCardNameAnswer(pending.interactionId, pending.gameStateId - 1, "Island") shouldBe false
                owner.submitCardNameAnswer(pending.interactionId, pending.gameStateId, " Grizzly Bears ") shouldBe true
                owner.submitCardNameAnswer(pending.interactionId, pending.gameStateId, "Island") shouldBe false
            }
            thread.join(3_000)
            assertSoftly {
                thread.isAlive shouldBe false
                failure.get() shouldBe null
                result.get() shouldBe " Grizzly Bears "
                owner.currentBlockingInteraction() shouldBe null
            }
        }

        for (close in listOf(false, true)) {
            test("card-name ${if (close) "shutdown" else "timeout"} wakes the engine without a fallback name") {
                val board = startPuzzleAtMain1(puzzle)
                val owner = board.bridge.cutCoordinator
                owner.drain(SeatId(1))
                val source =
                    ForgeCardId(
                        board.human
                            .getZone(forge.game.zone.ZoneType.Battlefield)
                            .cards
                            .first()
                            .id,
                    )
                val result = AtomicReference<String>()
                val failure = AtomicReference<Throwable>()
                val thread =
                    Thread {
                        runCatching {
                            owner.awaitCardName(
                                BlockingInteraction.CardName(source, "Choose a card name."),
                                if (close) 3_000 else 200,
                            )
                        }.onSuccess(result::set)
                            .onFailure(failure::set)
                    }.also { it.start() }
                val pending = awaitCardNameWindow(owner, failure)
                if (close) owner.shutdown()
                thread.join(3_000)
                assertSoftly {
                    thread.isAlive shouldBe false
                    result.get() shouldBe null
                    failure.get() shouldNotBe null
                    owner.failure() shouldNotBe null
                    owner.currentBlockingInteraction() shouldBe null
                    owner.submitCardNameAnswer(pending.interactionId, pending.gameStateId, "Island") shouldBe false
                }
            }
        }
    })

private fun awaitCardNameWindow(
    owner: MatchCutCoordinator,
    failure: AtomicReference<Throwable>,
): PublishedBlockingInteraction {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (owner.currentBlockingInteraction() == null && failure.get() == null && System.nanoTime() < deadline) Thread.onSpinWait()
    check(failure.get() == null) { "Input publication failed: ${failure.get()}" }
    return checkNotNull(owner.currentBlockingInteraction())
}
